package analyzer;

import common.GeneralLib;
import de.ser.doxis4.agentserver.UnifiedAgent;
import org.json.JSONObject;

import java.util.List;


public class Sample03 extends UnifiedAgent {

    @Override
    protected Object execute() {
        try{
            GeneralLib.load(this, true);
            GeneralLib._info("Started");
            /*
            if(GeneralLib.isStartedParalelWithObjIds(null)){
                int rdly = (new Random()).nextInt(30) + 1;
                return resultRestart("Paralel Agent [isStartedParalelWithObjIds] ...", rdly);
            }
            */
            String path = "c:/contract-analyze/sample-03/";
            List<String> originals = List.of(path + "hukuk_v1.docx", path + "hukuk_v2.docx");
            JSONObject result = AnalyzeHelper.contractAnalyze(path, originals);
            //GeneralLib._info("Result : " + result);

            GeneralLib._info("Finished");
            return resultSuccess("Ended successfully");

        } catch (Exception e) {
            //throw new RuntimeException(e);
            GeneralLib._error("Exception       : " + e.getMessage());
            GeneralLib._error("    Class       : " + e.getClass());
            GeneralLib._error("    Cause       : " + e.getCause());
            GeneralLib._error("    Stack-Trace : " + e.getStackTrace());
            return resultRestart("Exception : " + e.getMessage(),10);
        }
    }
}