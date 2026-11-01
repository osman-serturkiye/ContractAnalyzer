package analyzer;

import common.GeneralLib;
import de.ser.doxis4.agentserver.UnifiedAgent;
import org.json.JSONObject;


public class Sample06 extends UnifiedAgent {

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
            String path = "c:/contract-analyze/sample-06/";
            String original = path + "Approved_Document.docx";
            String signed = path + "Signed_Document.pdf";
            JSONObject result = AnalyzeHelper.contractAnalyze(path, original, signed);
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