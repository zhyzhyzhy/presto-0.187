/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.cost;

import com.facebook.presto.Session;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.planner.plan.PlanNodeId;

import java.util.Map;

/**
 * Interface of cost calculator.
 * <p>
 * It's responsibility is to provide approximation of cost of execution of plan node.
 * Example implementations may be based on table statistics or data samples.
 */
public interface CostCalculator
{
    Map<PlanNodeId, PlanNodeCost> calculateCostForPlan(Session session, Map<Symbol, Type> types, PlanNode planNode);

    default PlanNodeCost calculateCostForNode(Session session, Map<Symbol, Type> types, PlanNode planNode)
    {
        return calculateCostForPlan(session, types, planNode).get(planNode.getId());
    }
}
