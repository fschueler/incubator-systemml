package org.apache.sysml.runtime.controlprogram.context;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.hadoop.mapreduce.HadoopOutputFormat;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.Job;
import org.apache.sysml.api.DMLScript;
import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.DMLUnsupportedOperationException;
import org.apache.sysml.runtime.controlprogram.Program;
import org.apache.sysml.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysml.runtime.instructions.flink.data.DataSetObject;
import org.apache.sysml.runtime.instructions.flink.utils.RowIndexedInputFormat;
import org.apache.sysml.runtime.matrix.data.*;

import java.util.LinkedList;
import java.util.List;

public class FlinkExecutionContext extends ExecutionContext {

    private static final Log LOG = LogFactory.getLog(FlinkExecutionContext.class.getName());

    private static ExecutionEnvironment _execEnv = null;

    protected FlinkExecutionContext(Program prog) {
        this(true, prog);
    }

    protected FlinkExecutionContext(boolean allocateVars, Program prog) {
        super(allocateVars, prog);

        //if (OptimizerUtils.isHybridExecutionMode())
        initFlinkContext();
    }

    public ExecutionEnvironment getFlinkContext() {
        return _execEnv;
    }

    public DataSet<Tuple2<MatrixIndexes, MatrixBlock>> getBinaryBlockDataSetHandleForVariable(String varname)
            throws DMLRuntimeException, DMLUnsupportedOperationException {

        return (DataSet<Tuple2<MatrixIndexes, MatrixBlock>>) getDataSetHandleForVariable(varname, InputInfo.BinaryBlockInputInfo);
    }

    public DataSet<?> getDataSetHandleForVariable(String varname, InputInfo inputInfo)
            throws DMLRuntimeException, DMLUnsupportedOperationException {

        MatrixObject mo = getMatrixObject(varname);
        return getDataSetHandleForMatrixObject(mo, inputInfo);
    }

    public void setDataSetHandleForVariable(String varname, DataSet<Tuple2<MatrixIndexes, MatrixBlock>> ds) throws DMLRuntimeException {
        MatrixObject mo = getMatrixObject(varname);
        DataSetObject dsHandle = new DataSetObject(ds, varname);
        mo.setDataSetHandle(dsHandle);
    }

    public void addLineageDataSet(String varParent, String varChild) throws DMLRuntimeException {
        DataSetObject parent = getMatrixObject(varParent).getDataSetHandle();
        DataSetObject child = getMatrixObject(varChild).getDataSetHandle();

        parent.addLineageChild(child);
    }

    private DataSet<?> getDataSetHandleForMatrixObject(MatrixObject mo, InputInfo inputInfo)
            throws DMLRuntimeException, DMLUnsupportedOperationException {

        //FIXME this logic should be in matrix-object (see spark version of this method for more info)
        DataSet<?> dataSet = null;

        //CASE 1: rdd already existing (reuse if checkpoint or trigger
        //pending rdd operations if not yet cached but prevent to re-evaluate
        //rdd operations if already executed and cached
        if (mo.getDataSetHandle() != null
                && (mo.getDataSetHandle().isCheckpointed() || !mo.isCached(false))) {
            //return existing rdd handling (w/o input format change)
            dataSet = mo.getDataSetHandle().getDataSet();
        }
        //CASE 2: dirty in memory data or cached result of rdd operations
        else if (mo.isDirty() || mo.isCached(false)) {
            //get in-memory matrix block and parallelize it
            //w/ guarded parallelize (fallback to export, rdd from file if too large)
            boolean fromFile = false;
            // TODO (see spark case for large matrices)

            //default case
            MatrixBlock mb = mo.acquireRead(); //pin matrix in memory
            dataSet = toDataSet(getFlinkContext(), mb, (int) mo.getNumRowsPerBlock(), (int) mo.getNumColumnsPerBlock());
            mo.release(); //unpin matrix


            //keep rdd handle for future operations on it
            DataSetObject dshandle = new DataSetObject(dataSet, mo.getVarName());
            dshandle.setHDFSFile(fromFile);
            mo.setDataSetHandle(dshandle);
        }
        //CASE 3: non-dirty (file exists on HDFS)
        else {
            if (inputInfo == InputInfo.BinaryBlockInputInfo) {
                //TODO
                throw new DMLUnsupportedOperationException("Not supported yet");
            } else if (inputInfo == InputInfo.TextCellInputInfo || inputInfo == InputInfo.CSVInputInfo || inputInfo == InputInfo.MatrixMarketInputInfo) {
                dataSet = getFlinkContext().readFile(new RowIndexedInputFormat(), mo.getFileName());
            } else if (inputInfo == InputInfo.BinaryCellInputInfo) {
                //TODO
                throw new DMLUnsupportedOperationException("Not supported yet");
            } else {
                throw new DMLRuntimeException("Incorrect input format in getRDDHandleForVariable");
            }

            //keep dataset handle for future operations on it
            DataSetObject dataSetHandle = new DataSetObject(dataSet, mo.getVarName());
            dataSetHandle.setHDFSFile(true);
            mo.setDataSetHandle(dataSetHandle);
        }
        return dataSet;
    }

    private synchronized static void initFlinkContext() {
        _execEnv = ExecutionEnvironment.getExecutionEnvironment();
    }

    /**
     * Utility method for creating an RDD out of an in-memory matrix block.
     *
     * @param sc
     * @param block
     * @return
     * @throws DMLUnsupportedOperationException
     * @throws DMLRuntimeException
     */
    public static DataSet<Tuple2<MatrixIndexes, MatrixBlock>> toDataSet(ExecutionEnvironment env, MatrixBlock src, int brlen, int bclen)
            throws DMLRuntimeException, DMLUnsupportedOperationException {
        LinkedList<Tuple2<MatrixIndexes, MatrixBlock>> list = new LinkedList<Tuple2<MatrixIndexes, MatrixBlock>>();

        if (src.getNumRows() <= brlen
                && src.getNumColumns() <= bclen) {
            list.addLast(new Tuple2<MatrixIndexes, MatrixBlock>(new MatrixIndexes(1, 1), src));
        } else {
            boolean sparse = src.isInSparseFormat();

            //create and write subblocks of matrix
            for (int blockRow = 0; blockRow < (int) Math.ceil(src.getNumRows() / (double) brlen); blockRow++)
                for (int blockCol = 0; blockCol < (int) Math.ceil(src.getNumColumns() / (double) bclen); blockCol++) {
                    int maxRow = (blockRow * brlen + brlen < src.getNumRows()) ? brlen : src.getNumRows() - blockRow * brlen;
                    int maxCol = (blockCol * bclen + bclen < src.getNumColumns()) ? bclen : src.getNumColumns() - blockCol * bclen;

                    MatrixBlock block = new MatrixBlock(maxRow, maxCol, sparse);

                    int row_offset = blockRow * brlen;
                    int col_offset = blockCol * bclen;

                    //copy submatrix to block
                    src.sliceOperations(row_offset, row_offset + maxRow - 1,
                            col_offset, col_offset + maxCol - 1, block);

                    //append block to sequence file
                    MatrixIndexes indexes = new MatrixIndexes(blockRow + 1, blockCol + 1);
                    list.addLast(new Tuple2<MatrixIndexes, MatrixBlock>(indexes, block));
                }
        }

        return env.fromCollection(list);
    }

    @SuppressWarnings("unchecked")
    public static long writeDataSetToHDFS(DataSetObject dso, String path, OutputInfo oinfo) throws DMLRuntimeException {
        DataSet<Tuple2<MatrixIndexes, MatrixBlock>> lds = (DataSet<Tuple2<MatrixIndexes, MatrixBlock>>) dso.getDataSet();

        //TODO recompute nonzeros
        long nnz = 1L;

        //FIXME the following code has never been tested, it should write the DataSet to hdfs using the Outputformat
        // and K,V classes provided by the OutputInfo. Unfortunately, Flink has not nice `saveAsHadoopFile` method.

        // save as hadoop file

//        try {
//            JobConf conf = new JobConf();
//            conf.setOutputKeyClass(oinfo.outputKeyClass);
//            conf.setOutputValueClass(oinfo.outputValueClass);
//            conf.set("mapred.output.format.class", oinfo.outputFormatClass.getName());
//
//            Job job = Job.getInstance(conf);
//
//            OutputFormat of = (OutputFormat) oinfo.outputFormatClass.newInstance();
//            HadoopOutputFormat hadoopOF = new HadoopOutputFormat(of, job);
//
//            FileOutputFormat.setOutputPath(conf, new Path(path));
//
//            // Emit data using the Hadoop TextOutputFormat.
//            lds.output(hadoopOF);
//        } catch (Exception e) {
//            throw new DMLRuntimeException("Could not write DataSet to HDFS!", e);
//        }

        return nnz;
    }

    @SuppressWarnings("unchecked")
    public static MatrixBlock toMatrixBlock(DataSetObject dso, int rlen, int clen, int brlen, int bclen, long nnz)
            throws DMLRuntimeException {
        return toMatrixBlock(
                (DataSet<Tuple2<MatrixIndexes, MatrixBlock>>) dso.getDataSet(),
                rlen, clen, brlen, bclen, nnz);
    }

    public static MatrixBlock toMatrixBlock(DataSet<Tuple2<MatrixIndexes, MatrixBlock>> ds, int rlen, int clen, int brlen, int bclen, long nnz)
            throws DMLRuntimeException {

        long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;

        MatrixBlock out = null;
        List<Tuple2<MatrixIndexes, MatrixBlock>> list = null;
        try {
            list = ds.collect();
        } catch (Exception e) {
            throw new DMLRuntimeException("Could not collect dataset!", e);
        }

        if( rlen <= brlen && clen <= bclen ) //SINGLE BLOCK
        {
            //special case without copy and nnz maintenance
            if( list.size()>1 )
                throw new DMLRuntimeException("Expecting no more than one result block.");
            else if( list.size()==1 )
                out = list.get(0).f1;
            else //empty (e.g., after ops w/ outputEmpty=false)
                out = new MatrixBlock(rlen, clen, true);
        }
        else //MULTIPLE BLOCKS
        {
            //determine target sparse/dense representation
            long lnnz = (nnz >= 0) ? nnz : (long)rlen * clen;
            boolean sparse = MatrixBlock.evalSparseFormatInMemory(rlen, clen, lnnz);

            //create output matrix block (w/ lazy allocation)
            out = new MatrixBlock(rlen, clen, sparse);
            //copy blocks one-at-a-time into output matrix block
            for( Tuple2<MatrixIndexes,MatrixBlock> keyval : list )
            {
                //unpack index-block pair
                MatrixIndexes ix = keyval.f0;
                MatrixBlock block = keyval.f1;

                //compute row/column block offsets
                int row_offset = (int)(ix.getRowIndex()-1)*brlen;
                int col_offset = (int)(ix.getColumnIndex()-1)*bclen;
                int rows = block.getNumRows();
                int cols = block.getNumColumns();

                if( sparse ) { //SPARSE OUTPUT
                    //append block to sparse target in order to avoid shifting
                    //note: this append requires a final sort of sparse rows
                    out.appendToSparse(block, row_offset, col_offset);
                }
                else { //DENSE OUTPUT
                    out.copy( row_offset, row_offset+rows-1,
                            col_offset, col_offset+cols-1, block, false );
                }
            }

            //post-processing output matrix
            if( sparse )
                out.sortSparseRows();
            out.recomputeNonZeros();
            out.examSparsity();
        }

        return out;
    }
}


