# Presto 0.187 精简DEBUG版本  
* presto-parser模块的antlr已经完成生成完毕，不需要再生成  
* connector仅保留了Mysql，exampleHttp，memory，blackhole，local-file  
* connector默认启用Mysql  

主要可以查看Sql的Parser和Plan流程  
配合浏览器可视化operator,pipeline效果更佳

# 导入Idea 
## Idea 启动Presto Server  
* Main Class: `com.facebook.presto.server.PrestoServer`  
* VM Options: `-ea -XX:+UseG1GC -XX:G1HeapRegionSize=32M -XX:+UseGCOverheadLimit -XX:+ExplicitGCInvokesConcurrent -Xmx2G -Dconfig=etc/config.properties -Dlog.levels-file=etc/log.properties`
* Working directory: `$MODULE_DIR$`
* Use classpath of module: `presto-main`

## Idea启动CLI
* Main Class: `com.facebook.presto.cli.Presto`

# 打包CLI为可执行文件
```shell script
cd presto-cli
mvn package -Dmaven.test.skip=true
```
在target目录会生成`presto-cli-0.187-executable.jar`  
直接运行`./presto-cli-0.187-executable.jar`即可

# Mysql配置
`presto-main/etc/catalog/mysql.properties`

配置url，user，password即可  
**注意0.187的URL不能带database**    
如：
```
connector.name=mysql
connection-url=jdbc:mysql://127.0.0.1:3306/
connection-user=root
connection-password=
```

# 浏览器
运行数据以及执行计划可视化在`http://localhost:8080/`

# CLI运行
例如：`select * from mysql.mini_program.admin;`


