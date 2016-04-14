package org.apache.sysml.runtime.instructions.flink;

import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.operators.DataSource;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.mesos.Protos;
import org.apache.sysml.api.DMLScript;
import org.apache.sysml.api.FlinkMLOutput;
import org.apache.sysml.api.MLContext;
import org.apache.sysml.api.MLOutput;
import org.apache.sysml.lops.MMTSJ;
import org.apache.sysml.parser.Expression;
import org.apache.sysml.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysml.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysml.runtime.controlprogram.context.ExecutionContextFactory;
import org.apache.sysml.runtime.controlprogram.context.FlinkExecutionContext;
import org.apache.sysml.runtime.instructions.cp.VariableCPInstruction;
import org.apache.sysml.runtime.instructions.flink.data.DataSetObject;
import org.apache.sysml.runtime.instructions.flink.utils.DataSetConverterUtils;
import org.apache.sysml.runtime.instructions.flink.utils.RowIndexedInputFormat;
import org.apache.sysml.runtime.instructions.mr.ReblockInstruction;
import org.apache.sysml.runtime.matrix.MatrixCharacteristics;
import org.apache.sysml.runtime.matrix.MatrixDimensionsMetaData;
import org.apache.sysml.runtime.matrix.MatrixFormatMetaData;
import org.apache.sysml.runtime.matrix.data.InputInfo;
import org.apache.sysml.runtime.matrix.data.MatrixBlock;
import org.apache.sysml.runtime.matrix.data.MatrixIndexes;
import org.apache.sysml.runtime.matrix.data.OutputInfo;
import org.junit.Ignore;
import org.junit.Test;

import java.util.HashMap;

public class TsmmFLInstructionTest {

    /**
     * Tests the instructions that result from the following DML script:
     *
     * m = read("tesfile", rows=306, cols=4)
     * mm = t(m) %*% m
     * write(mm, "outputPath", format="csv")
     *
     * @throws Exception
     */
    @Ignore
    public void testTSMMWithInstructions() throws Exception {
        // input data and blocking parameters
        String testFile = getClass().getClassLoader().getResource("flink/haberman.data").getFile();
        String outputPath = "/tmp/sysml/output/tsmm_out.csv";

        DMLScript.rtplatform = DMLScript.RUNTIME_PLATFORM.FLINK;

        // get execution context
        FlinkExecutionContext flec = (FlinkExecutionContext) ExecutionContextFactory.createContext();

        //execute variable isntructions to set up scratch space
        VariableCPInstruction readVar1   = VariableCPInstruction.parseInstruction(   "CP°createvar°pREADm°" + testFile + "°false°csv°306°4°-1°-1°-1°false°false°,°true°0.0");
        VariableCPInstruction createVar1 = VariableCPInstruction.parseInstruction(   "CP°createvar°_mVar1°scratch_space//_p22279_127.0.1.1//_t0/temp1°true°binaryblock°306°4°1000°1000°-1°false");
        ReblockFLInstruction  reblock    = ReblockFLInstruction.parseInstruction(    "FLINK°rblk°pREADm·MATRIX·DOUBLE°_mVar1·MATRIX·DOUBLE°1000°1000°true");
        VariableCPInstruction createvar2 = VariableCPInstruction.parseInstruction(   "CP°createvar°_mVar2°scratch_space//_p22279_127.0.1.1//_t0/temp2°true°binaryblock°306°4°1000°1000°-1°false");
        CheckpointFLInstruction chkpnt   = CheckpointFLInstruction.parseInstruction( "FLINK°chkpoint°_mVar1·MATRIX·DOUBLE°_mVar2·MATRIX·DOUBLE°MEMORY_AND_DISK");
        VariableCPInstruction rmVar1     = VariableCPInstruction.parseInstruction(   "CP°rmvar°_mVar1");
        VariableCPInstruction createVar3 = VariableCPInstruction.parseInstruction(   "CP°createvar°_mVar3°scratch_space//_p22279_127.0.1.1//_t0/temp3°true°binaryblock°4°4°1000°1000°-1°false");
        TsmmFLInstruction     inst       = TsmmFLInstruction.parseInstruction(       "FLINK°tsmm°_mVar2·MATRIX·DOUBLE°_mVar3·MATRIX·DOUBLE°LEFT");
        VariableCPInstruction rmVar2     = VariableCPInstruction.parseInstruction(   "CP°rmvar°_mVar2");
        WriteFLInstruction    wrVar3     = WriteFLInstruction.parseInstruction(      "FLINK°write°_mVar3·MATRIX·DOUBLE°" + outputPath + "·SCALAR·STRING·true°csv·SCALAR·STRING·true°false°,°false°true");
        VariableCPInstruction rmVar3     = VariableCPInstruction.parseInstruction(   "CP°rmvar°_mVar3");

        // execute the instructions
        readVar1.processInstruction(flec);
        createVar1.processInstruction(flec);
        reblock.processInstruction(flec);
        createvar2.processInstruction(flec);
        chkpnt.processInstruction(flec);
        rmVar1.processInstruction(flec);
        createVar3.processInstruction(flec);
        inst.processInstruction(flec);
        rmVar2.processInstruction(flec);
        wrVar3.processInstruction(flec);
        rmVar3.processInstruction(flec);

        flec.getFlinkContext().execute("WriteFLInstruction");
    }

    @Ignore
    public void testTSMMWithMLConext() throws Exception {
        // input data and blocking parameters
        String testFile = getClass().getClassLoader().getResource("flink/haberman.data").getFile();
        int numRowsPerBlock = 10;
        int numColsPerBlock = 10;

        // the dml script
        String dmlscript = String.join("\n",
                " Xin = read(\" \")   ",
                " Xout = t(Xin) %*% Xin  ",
                " write(Xout, \" \") ");

        // load and transform data into dataset with systemml binary format
        ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();
        DataSource<Tuple2<Integer, String>> input = env.readFile(new RowIndexedInputFormat(), testFile);
        MatrixCharacteristics mcOut = new MatrixCharacteristics(0L, 0L, numRowsPerBlock, numColsPerBlock);
        DataSet<Tuple2<MatrixIndexes, MatrixBlock>> mat = DataSetConverterUtils.csvToBinaryBlock(env, input, mcOut, false, ",", false, 0.0);

        // create MLContext
        MLContext mlContext = new MLContext(env);
        mlContext.registerInput("Xin", mat, mcOut);
        mlContext.registerOutput("Xout");

        // execute dml script
        MLOutput out = mlContext.executeScript(dmlscript);

        // get the output
        DataSet<Tuple2<MatrixIndexes, MatrixBlock>> output = ((FlinkMLOutput) out).getBinaryBlockedDataSet("Xout");
        System.out.println(output);
    }
}
