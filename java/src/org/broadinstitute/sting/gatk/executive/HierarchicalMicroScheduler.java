package org.broadinstitute.sting.gatk.executive;

import org.broadinstitute.sting.gatk.walkers.Walker;
import org.broadinstitute.sting.gatk.walkers.TreeReducible;
import org.broadinstitute.sting.gatk.datasources.shards.ShardStrategy;
import org.broadinstitute.sting.gatk.datasources.shards.Shard;
import org.broadinstitute.sting.gatk.datasources.simpleDataSources.SAMDataSource;
import org.broadinstitute.sting.gatk.datasources.simpleDataSources.ReferenceOrderedDataSource;
import org.broadinstitute.sting.gatk.io.*;
import org.broadinstitute.sting.utils.StingException;
import org.broadinstitute.sting.utils.fasta.IndexedFastaSequenceFile;
import org.broadinstitute.sting.utils.threading.ThreadPoolMonitor;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.JMException;
import java.util.Queue;
import java.util.LinkedList;
import java.util.Collection;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.lang.management.ManagementFactory;

/**
 * A microscheduler that schedules shards according to a tree-like structure.
 * Requires a special walker tagged with a 'TreeReducible' interface.
 */
public class HierarchicalMicroScheduler extends MicroScheduler implements HierarchicalMicroSchedulerMBean, ReduceTree.TreeReduceNotifier {
    /**
     * How many outstanding output merges are allowed before the scheduler stops
     * allowing new processes and starts merging flat-out.
     */
    private static final int MAX_OUTSTANDING_OUTPUT_MERGES = 50;

    /** Manage currently running threads. */
    private ExecutorService threadPool;

    /**
     * A thread local output tracker for managing output per-thread.
     */
    private ThreadLocalOutputTracker outputTracker = new ThreadLocalOutputTracker();

    private final Queue<Shard> traverseTasks = new LinkedList<Shard>();
    private final Queue<TreeReduceTask> reduceTasks = new LinkedList<TreeReduceTask>();

    /**
     * Keep a queue of shard traversals, and constantly monitor it to see what output
     * merge tasks remain.
     * TODO: Integrate this into the reduce tree.
     */
    private final Queue<ShardTraverser> outputMergeTasks = new LinkedList<ShardTraverser>();

    /** How many total tasks were in the queue at the start of run. */
    private int totalTraversals = 0;

    /** How many shard traversals have run to date? */
    private int totalCompletedTraversals = 0;

    /** What is the total time spent traversing shards? */
    private long totalShardTraverseTime = 0;

    /** What is the total time spent tree reducing shard output? */
    private long totalTreeReduceTime = 0;

    /** How many tree reduces have been completed? */
    private long totalCompletedTreeReduces = 0;

    /** What is the total time spent merging output? */
    private long totalOutputMergeTime = 0;

    /**
     * Create a new hierarchical microscheduler to process the given reads and reference.
     *
     * @param reads         Reads file(s) to process.
     * @param reference     Reference for driving the traversal.
     * @param nThreadsToUse maximum number of threads to use to do the work
     */
    protected HierarchicalMicroScheduler( Walker walker, SAMDataSource reads, IndexedFastaSequenceFile reference, Collection<ReferenceOrderedDataSource> rods, int nThreadsToUse ) {
        super(walker, reads, reference, rods);
        this.threadPool = Executors.newFixedThreadPool(nThreadsToUse);

        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName("org.broadinstitute.sting.gatk.executive:type=HierarchicalMicroScheduler");
            mbs.registerMBean(this, name);
        }
        catch (JMException ex) {
            throw new StingException("Unable to register microscheduler with JMX", ex);
        }
    }

    public Object execute( Walker walker, ShardStrategy shardStrategy, int maxIterations ) {
        // Fast fail for walkers not supporting TreeReducible interface.
        if (!( walker instanceof TreeReducible ))
            throw new IllegalArgumentException("Hierarchical microscheduler only works with TreeReducible walkers");

        // Having maxiterations in the execute method is a holdover from the old TraversalEngine days.
        // Lets do something else with this.
        traversalEngine.setMaximumIterations(maxIterations);

        ReduceTree reduceTree = new ReduceTree(this);

        walker.initialize();

        for (Shard shard : shardStrategy)
            traverseTasks.add(shard);
        totalTraversals = traverseTasks.size();

        while (isShardTraversePending() || isTreeReducePending()) {
            // Too many files sitting around taking up space?  Merge them.
            if (isMergeLimitExceeded())
                mergeExistingOutput(false);

            // Wait for the next slot in the queue to become free.
            waitForFreeQueueSlot();

            // Pick the next most appropriate task and run it.  In the interest of
            // memory conservation, hierarchical reduces always run before traversals.
            if (isTreeReduceReady())
                queueNextTreeReduce(walker);
            else if (isShardTraversePending())
                queueNextShardTraverse(walker, reduceTree);
        }

        // Merge any lingering output files.  If these files aren't ready,
        // sit around and wait for them, then merge them.
        mergeExistingOutput(true);

        threadPool.shutdown();

        Object result = null;
        try {
            result = reduceTree.getResult().get();
        }
        catch (Exception ex) {
            throw new StingException("Unable to retrieve result", ex);
        }

        walker.onTraversalDone(result);        

        printOnTraversalDone(result);

        getOutputTracker().close();

        return result;
    }

    /**
     * @{inheritDoc}
     */
    public OutputTracker getOutputTracker() {
        return outputTracker;
    }

    /**
     * Returns true if there are unscheduled shard traversal waiting to run.
     *
     * @return true if a shard traversal is waiting; false otherwise.
     */
    protected boolean isShardTraversePending() {
        return traverseTasks.size() > 0;
    }

    /**
     * Returns true if there are tree reduces that can be run without
     * blocking.
     *
     * @return true if a tree reduce is ready; false otherwise.
     */
    protected boolean isTreeReduceReady() {
        if (reduceTasks.size() == 0)
            return false;
        return reduceTasks.peek().isReadyForReduce();
    }

    /**
     * Returns true if there are tree reduces that need to be run before
     * the computation is complete.  Returns true if any entries are in the queue,
     * blocked or otherwise.
     *
     * @return true if a tree reduce is pending; false otherwise.
     */
    protected boolean isTreeReducePending() {
        return reduceTasks.size() > 0;
    }

    /**
     * Returns whether the maximum number of files is sitting in the temp directory
     * waiting to be merged back in.
     *
     * @return True if the merging needs to take priority.  False otherwise.
     */
    protected boolean isMergeLimitExceeded() {
        int pendingTasks = 0;
        for( ShardTraverser shardTraverse: outputMergeTasks ) {
            if( !shardTraverse.isComplete() )
                break;
            pendingTasks++;
        }
        return (outputMergeTasks.size() >= MAX_OUTSTANDING_OUTPUT_MERGES);
    }

    /**
     * Merging all output that's sitting ready in the OutputMerger queue into
     * the final data streams.
     */
    protected void mergeExistingOutput( boolean wait ) {
        long startTime = System.currentTimeMillis();

        // Create a list of the merge tasks that will be performed in this run of the mergeExistingOutput().
        Queue<ShardTraverser> mergeTasksInSession = new LinkedList<ShardTraverser>();
        while( !outputMergeTasks.isEmpty() ) {
            ShardTraverser traverser = outputMergeTasks.peek();

            // If the next traversal isn't done and we're not supposed to wait, we've found our working set.  Continue.
            if( !traverser.isComplete() && !wait )
                break;

            outputMergeTasks.remove();
            mergeTasksInSession.add(traverser);
        }

        // Actually run through, merging the tasks in the working queue.
        for( ShardTraverser traverser: mergeTasksInSession ) {
            if( !traverser.isComplete() )
                traverser.waitForComplete();

            OutputMergeTask mergeTask = traverser.getOutputMergeTask();
            if( mergeTask != null )
                mergeTask.merge();
        }

        long endTime = System.currentTimeMillis();

        totalOutputMergeTime += ( endTime - startTime );
    }

    /**
     * Queues the next traversal of a walker from the traversal tasks queue.
     *
     * @param walker     Walker to apply to the dataset.
     * @param reduceTree Tree of reduces to which to add this shard traverse.
     */
    protected Future queueNextShardTraverse( Walker walker, ReduceTree reduceTree ) {
        if (traverseTasks.size() == 0)
            throw new IllegalStateException("Cannot traverse; no pending traversals exist.");

        Shard shard = traverseTasks.remove();

        ShardTraverser traverser = new ShardTraverser(this,
                traversalEngine,
                walker,
                shard,
                getShardDataProvider(shard),
                outputTracker);

        Future traverseResult = threadPool.submit(traverser);

        // Add this traverse result to the reduce tree.  The reduce tree will call a callback to throw its entries on the queue.
        reduceTree.addEntry(traverseResult);
        outputMergeTasks.add(traverser);

        // No more data?  Let the reduce tree know so it can finish processing what it's got.
        if (!isShardTraversePending())
            reduceTree.complete();

        return traverseResult;
    }

    /** Pulls the next reduce from the queue and runs it. */
    protected void queueNextTreeReduce( Walker walker ) {
        if (reduceTasks.size() == 0)
            throw new IllegalStateException("Cannot reduce; no pending reduces exist.");
        TreeReduceTask reducer = reduceTasks.remove();
        reducer.setWalker((TreeReducible) walker);

        threadPool.submit(reducer);
    }

    /** Blocks until a free slot appears in the thread queue. */
    protected void waitForFreeQueueSlot() {
        ThreadPoolMonitor monitor = new ThreadPoolMonitor();
        synchronized (monitor) {
            threadPool.submit(monitor);
            monitor.watch();
        }
    }

    /**
     * Callback for adding reduce tasks to the run queue.
     *
     * @return A new, composite future of the result of this reduce.
     */
    public Future notifyReduce( Future lhs, Future rhs ) {
        TreeReduceTask reducer = new TreeReduceTask(new TreeReducer(this, lhs, rhs));
        reduceTasks.add(reducer);
        return reducer;
    }


    /** A small wrapper class that provides the TreeReducer interface along with the FutureTask semantics. */
    private class TreeReduceTask extends FutureTask {
        private TreeReducer treeReducer = null;

        public TreeReduceTask( TreeReducer treeReducer ) {
            super(treeReducer);
            this.treeReducer = treeReducer;
        }

        public void setWalker( TreeReducible walker ) {
            treeReducer.setWalker(walker);
        }

        public boolean isReadyForReduce() {
            return treeReducer.isReadyForReduce();
        }
    }

    /**
     * Used by the ShardTraverser to report time consumed traversing a given shard.
     *
     * @param shardTraversalTime Elapsed time traversing a given shard.
     */
    synchronized void reportShardTraverseTime( long shardTraversalTime ) {
        totalShardTraverseTime += shardTraversalTime;
        totalCompletedTraversals++;
    }

    /**
     * Used by the TreeReducer to report time consumed reducing two shards.
     *
     * @param treeReduceTime Elapsed time reducing two shards.
     */
    synchronized void reportTreeReduceTime( long treeReduceTime ) {
        totalTreeReduceTime += treeReduceTime;
        totalCompletedTreeReduces++;

    }

    /** {@inheritDoc} */
    public int getTotalNumberOfShards() {
        return totalTraversals;
    }

    /** {@inheritDoc} */
    public int getRemainingNumberOfShards() {
        return traverseTasks.size();
    }

    /** {@inheritDoc} */
    public int getNumberOfTasksInReduceQueue() {
        return reduceTasks.size();
    }

    /** {@inheritDoc} */
    public int getNumberOfTasksInIOQueue() {
        synchronized( outputMergeTasks ) {
            return outputMergeTasks.size();
        }
    }

    /** {@inheritDoc} */
    public long getTotalShardTraverseTimeMillis() {
        return totalShardTraverseTime;
    }

    /** {@inheritDoc} */
    public long getAvgShardTraverseTimeMillis() {
        if (totalCompletedTraversals == 0)
            return 0;
        return totalShardTraverseTime / totalCompletedTraversals;
    }

    /** {@inheritDoc} */
    public long getTotalTreeReduceTimeMillis() {
        return totalTreeReduceTime;
    }

    /** {@inheritDoc} */
    public long getAvgTreeReduceTimeMillis() {
        if (totalCompletedTreeReduces == 0)
            return 0;
        return totalTreeReduceTime / totalCompletedTreeReduces;
    }

    /** {@inheritDoc} */
    public long getTotalOutputMergeTimeMillis() {
        return totalOutputMergeTime;
    }
}
