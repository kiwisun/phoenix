package org.apache.phoenix.calcite;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.phoenix.compile.FromCompiler;
import org.apache.phoenix.compile.QueryPlan;
import org.apache.phoenix.compile.RowProjector;
import org.apache.phoenix.compile.SequenceManager;
import org.apache.phoenix.compile.StatementContext;
import org.apache.phoenix.compile.GroupByCompiler.GroupBy;
import org.apache.phoenix.compile.OrderByCompiler.OrderBy;
import org.apache.phoenix.execute.ClientAggregatePlan;
import org.apache.phoenix.jdbc.PhoenixStatement;
import org.apache.phoenix.schema.TableRef;

public class PhoenixClientAggregate extends PhoenixAggregate {

    public PhoenixClientAggregate(RelOptCluster cluster, RelTraitSet traits,
            RelNode child, boolean indicator, ImmutableBitSet groupSet,
            List<ImmutableBitSet> groupSets, List<AggregateCall> aggCalls) {
        super(cluster, traits, child, indicator, groupSet, groupSets, aggCalls);
    }

    @Override
    public PhoenixClientAggregate copy(RelTraitSet traits, RelNode input,
            boolean indicator, ImmutableBitSet groupSet,
            List<ImmutableBitSet> groupSets, List<AggregateCall> aggregateCalls) {
        return new PhoenixClientAggregate(getCluster(), traits, input, indicator, groupSet, groupSets, aggregateCalls);
    }
    
    @Override
    public RelOptCost computeSelfCost(RelOptPlanner planner) {
        return super.computeSelfCost(planner)
                .multiplyBy(PHOENIX_FACTOR);
    }

    @Override
    public QueryPlan implement(Implementor implementor) {
        assert getConvention() == getInput().getConvention();
        
        QueryPlan plan = implementor.visitInput(0, (PhoenixRel) getInput());
        
        TableRef tableRef = implementor.getTableRef();
        PhoenixStatement stmt = plan.getContext().getStatement();
        StatementContext context;
        try {
            context = new StatementContext(stmt, FromCompiler.getResolver(tableRef), new Scan(), new SequenceManager(stmt));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }        
        GroupBy groupBy = super.getGroupBy(implementor);       
        super.serializeAggregators(implementor, context, groupBy.isEmpty());
        
        QueryPlan aggPlan = new ClientAggregatePlan(context, plan.getStatement(), tableRef, RowProjector.EMPTY_PROJECTOR, null, null, OrderBy.EMPTY_ORDER_BY, groupBy, null, plan);
        
        return PhoenixAggregate.wrapWithProject(implementor, aggPlan, groupBy.getKeyExpressions(), Arrays.asList(context.getAggregationManager().getAggregators().getFunctions()));
    }

}
