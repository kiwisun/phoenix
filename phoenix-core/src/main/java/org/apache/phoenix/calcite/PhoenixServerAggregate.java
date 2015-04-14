package org.apache.phoenix.calcite;

import java.util.Arrays;
import java.util.List;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.phoenix.compile.GroupByCompiler.GroupBy;
import org.apache.phoenix.compile.QueryPlan;
import org.apache.phoenix.compile.RowProjector;
import org.apache.phoenix.compile.StatementContext;
import org.apache.phoenix.compile.OrderByCompiler.OrderBy;
import org.apache.phoenix.execute.AggregatePlan;
import org.apache.phoenix.execute.HashJoinPlan;
import org.apache.phoenix.execute.ScanPlan;
import org.apache.phoenix.parse.SelectStatement;

public class PhoenixServerAggregate extends PhoenixAggregate {

    public PhoenixServerAggregate(RelOptCluster cluster, RelTraitSet traits,
            RelNode child, boolean indicator, ImmutableBitSet groupSet,
            List<ImmutableBitSet> groupSets, List<AggregateCall> aggCalls) {
        super(cluster, traits, child, indicator, groupSet, groupSets, aggCalls);
    }

    @Override
    public PhoenixServerAggregate copy(RelTraitSet traits, RelNode input, boolean indicator, ImmutableBitSet groupSet, List<ImmutableBitSet> groupSets, List<AggregateCall> aggregateCalls) {
        return new PhoenixServerAggregate(getCluster(), traits, input, indicator, groupSet, groupSets, aggregateCalls);
    }
    
    @Override
    public RelOptCost computeSelfCost(RelOptPlanner planner) {
        return super.computeSelfCost(planner)
                .multiplyBy(SERVER_FACTOR)
                .multiplyBy(PHOENIX_FACTOR);
    }

    @Override
    public QueryPlan implement(Implementor implementor) {
        assert getConvention() == getInput().getConvention();
        
        QueryPlan plan = implementor.visitInput(0, (PhoenixRel) getInput());
        assert (plan instanceof ScanPlan || plan instanceof HashJoinPlan) 
                && plan.getLimit() == null;
        
        ScanPlan basePlan;
        if (plan instanceof ScanPlan) {
            basePlan = (ScanPlan) plan;
        } else {
            QueryPlan delegate = ((HashJoinPlan) plan).getDelegate();
            assert delegate instanceof ScanPlan;
            basePlan = (ScanPlan) delegate;
        }
        
        StatementContext context = basePlan.getContext();        
        GroupBy groupBy = super.getGroupBy(implementor);       
        super.serializeAggregators(implementor, context, groupBy.isEmpty());
        
        QueryPlan aggPlan = new AggregatePlan(context, basePlan.getStatement(), basePlan.getTableRef(), RowProjector.EMPTY_PROJECTOR, null, OrderBy.EMPTY_ORDER_BY, null, groupBy, null);
        if (plan instanceof HashJoinPlan) {        
            HashJoinPlan hashJoinPlan = (HashJoinPlan) plan;
            aggPlan = HashJoinPlan.create((SelectStatement) (plan.getStatement()), aggPlan, hashJoinPlan.getJoinInfo(), hashJoinPlan.getSubPlans());
        }
        
        return PhoenixAggregate.wrapWithProject(implementor, aggPlan, groupBy.getKeyExpressions(), Arrays.asList(context.getAggregationManager().getAggregators().getFunctions()));
    }

}
