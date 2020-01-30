#!/bin/bash

set -e

function retry() {
  END=$(($(date +%s) + 600))

  while (( $(date +%s) < $END )); do
    set +e
    "$@"
    EXIT_CODE=$?
    set -e

    if [[ ${EXIT_CODE} == 0 ]]; then
      break
    fi
    sleep 5
  done

  return ${EXIT_CODE}
}

function hadoop_master_container(){
  environment_compose ps -q hadoop-master
}

function check_hadoop() {
  docker exec $(hadoop_master_container) supervisorctl status hive-server2 | grep -i running
}

function stop_unnecessary_hadoop_services() {
  HADOOP_MASTER_CONTAINER=$(hadoop_master_container)
  docker exec ${HADOOP_MASTER_CONTAINER} supervisorctl status
  docker exec ${HADOOP_MASTER_CONTAINER} supervisorctl stop mapreduce-historyserver
  docker exec ${HADOOP_MASTER_CONTAINER} supervisorctl stop zookeeper
}

function run_in_application_runner_container() {
  local CONTAINER_NAME=$( environment_compose run -d application-runner "$@" )
  echo "Showing logs from $CONTAINER_NAME:"
  docker logs -f $CONTAINER_NAME
  return $( docker inspect --format '{{.State.ExitCode}}' $CONTAINER_NAME )
}

function check_presto() {
  run_in_application_runner_container \
    java -jar "/docker/volumes/presto-cli/presto-cli-executable.jar" \
    ${CLI_ARGUMENTS} \
    --execute "SHOW CATALOGS" | grep -i hive
}

function run_product_tests() {
  local REPORT_DIR="${PRODUCT_TESTS_ROOT}/target/test-reports"
  rm -rf "${REPORT_DIR}"
  mkdir -p "${REPORT_DIR}"
  run_in_application_runner_container \
    java "-Djava.util.logging.config.file=/docker/volumes/conf/tempto/logging.properties" \
    -Duser.timezone=Asia/Kathmandu \
    ${TLS_CERTIFICATE} \
    -jar "/docker/volumes/presto-product-tests/presto-product-tests-executable.jar" \
    --report-dir "/docker/volumes/test-reports" \
    --config-local "/docker/volumes/tempto/tempto-configuration-local.yaml" \
    "$@" \
    &
  PRODUCT_TESTS_PROCESS_ID=$!
  wait ${PRODUCT_TESTS_PROCESS_ID}
  local PRODUCT_TESTS_EXIT_CODE=$?

  #make the files in $REPORT_DIR modifiable by everyone, as they were created by root (by docker)
  run_in_application_runner_container chmod -R 777 "/docker/volumes/test-reports"

  return ${PRODUCT_TESTS_EXIT_CODE}
}

# docker-compose down is not good enough because it's ignores services created with "run" command
function stop_application_runner_containers() {
  local ENVIRONMENT=$1
  APPLICATION_RUNNER_CONTAINERS=$(environment_compose ps -q application-runner)
  for CONTAINER_NAME in ${APPLICATION_RUNNER_CONTAINERS}
  do
    echo "Stopping: ${CONTAINER_NAME}"
    docker stop ${CONTAINER_NAME}
    echo "Container stopped: ${CONTAINER_NAME}"
  done
  echo "Removing dead application-runner containers"
  local CONTAINERS=`docker ps -aq --no-trunc --filter status=dead --filter status=exited --filter name=common_application-runner`
  for CONTAINER in ${CONTAINERS};
  do
    docker rm -v "${CONTAINER}"
  done
}

function stop_all_containers() {
  local ENVIRONMENT
  for ENVIRONMENT in $(getAvailableEnvironments)
  do
     stop_docker_compose_containers ${ENVIRONMENT}
  done
}

function stop_docker_compose_containers() {
  local ENVIRONMENT=$1
  RUNNING_CONTAINERS=$(environment_compose ps -q)

  if [[ ! -z ${RUNNING_CONTAINERS} ]]; then
    # stop application runner containers started with "run"
    stop_application_runner_containers ${ENVIRONMENT}

    # stop containers started with "up", removing their volumes
    # Some containers (SQL Server) fail to stop on Travis after running the tests. We don't have an easy way to
    # reproduce this locally. Since all the tests complete successfully, we ignore this failure.
    environment_compose down -v || true
  fi

  echo "Docker compose containers stopped: [$ENVIRONMENT]"
}

function prefetch_images_silently() {
  local IMAGES=$( docker_images_used )
  for IMAGE in $IMAGES
  do
    echo "Pulling docker image [$IMAGE]"
    docker pull $IMAGE > /dev/null
  done
}

function docker_images_used() {
  environment_compose config | grep 'image:' | awk '{ print $2 }' | sort | uniq
}

function environment_compose() {
  "${DOCKER_CONF_LOCATION}/${ENVIRONMENT}/compose.sh" "$@"
}

function cleanup() {
  stop_application_runner_containers ${ENVIRONMENT}

  if [[ "${LEAVE_CONTAINERS_ALIVE_ON_EXIT}" != "true" ]]; then
    stop_docker_compose_containers ${ENVIRONMENT}
  fi

  # Ensure that the logs processes are terminated.
  # In most cases after the docker containers are stopped, logs processes must be terminated.
  # However when the `LEAVE_CONTAINERS_ALIVE_ON_EXIT` is set, docker containers are not being terminated.
  # Redirection of system error is supposed to hide the `process does not exist` and `process terminated` messages
  if [[ ! -z ${HADOOP_LOGS_PID} ]]; then
    kill ${HADOOP_LOGS_PID} 2>/dev/null || true
  fi
  if [[ ! -z ${PRESTO_LOGS_PID} ]]; then
    kill ${PRESTO_LOGS_PID} 2>/dev/null || true
  fi

  # docker logs processes are being terminated as soon as docker container are stopped
  # wait for docker logs termination
  wait 2>/dev/null || true
}

function terminate() {
  trap - INT TERM EXIT
  set +e
  cleanup
  exit 130
}

function getAvailableEnvironments() {
  for i in $(ls -d $DOCKER_CONF_LOCATION/*/); do echo ${i%%/}; done \
     | grep -v files | grep -v common | xargs -n1 basename
}

source ${BASH_SOURCE%/*}/locations.sh

ENVIRONMENT=$1

# Get the list of valid environments
if [[ ! -f "$DOCKER_CONF_LOCATION/$ENVIRONMENT/compose.sh" ]]; then
   echo "Usage: run_on_docker.sh <`getAvailableEnvironments | tr '\n' '|'`> <product test args>"
   exit 1
fi

shift 1

PRESTO_SERVICES="presto-master"
if [[ "$ENVIRONMENT" == "multinode" ]]; then
   PRESTO_SERVICES="${PRESTO_SERVICES} presto-worker"
elif [[ "$ENVIRONMENT" == "multinode-tls" ]]; then
   PRESTO_SERVICES="${PRESTO_SERVICES} presto-worker-1 presto-worker-2"
fi

CLI_ARGUMENTS="--server presto-master:8080"
if [[ "$ENVIRONMENT" == "multinode-tls" ]]; then
    CLI_ARGUMENTS="--server https://presto-master.docker.cluster:7778 --keystore-path /docker/volumes/conf/presto/etc/docker.cluster.jks --keystore-password 123456"
fi

# check docker and docker compose installation
docker-compose version
docker version

stop_all_containers

if [[ "$CONTINUOUS_INTEGRATION" == 'true' ]]; then
    prefetch_images_silently
    # This has to be done after fetching the images
    # or will present stale / no data for images that changed.
    echo "Docker images versions:"
    docker_images_used | xargs -n 1 docker inspect --format='ID: {{.ID}}, tags: {{.RepoTags}}'
fi

# catch terminate signals
trap terminate INT TERM EXIT

if [[ "$ENVIRONMENT" == "singlenode" || "$ENVIRONMENT" == "multinode" ]]; then
  EXTERNAL_SERVICES="hadoop-master mysql postgres cassandra"
elif [[ "$ENVIRONMENT" == "singlenode-sqlserver" ]]; then
  EXTERNAL_SERVICES="hadoop-master sqlserver"
elif [[ "$ENVIRONMENT" == "singlenode-ldap" ]]; then
  EXTERNAL_SERVICES="hadoop-master ldapserver"
else
  EXTERNAL_SERVICES="hadoop-master"
fi
environment_compose up -d ${EXTERNAL_SERVICES}

# start docker logs for the external services
environment_compose logs --no-color -f ${EXTERNAL_SERVICES} &

HADOOP_LOGS_PID=$!

# wait until hadoop processes is started
retry check_hadoop
stop_unnecessary_hadoop_services

# start presto containers
environment_compose up -d ${PRESTO_SERVICES}

# start docker logs for presto containers
environment_compose logs --no-color -f ${PRESTO_SERVICES} &
PRESTO_LOGS_PID=$!

# wait until presto is started
retry check_presto

# run product tests
set +e
run_product_tests "$@"
EXIT_CODE=$?
set -e

# execution finished successfully
# disable trap, run cleanup manually
trap - INT TERM EXIT
cleanup

exit ${EXIT_CODE}
