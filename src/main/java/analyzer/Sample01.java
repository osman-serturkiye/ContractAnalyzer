package analyzer;

import ai.Router;
import config.AppConfig;
import de.ser.doxis4.agentserver.UnifiedAgent;

import common.GeneralLib;
import org.json.JSONObject;
import report.ReportBuilder;
import steps.Pipeline;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


public class Sample01 extends UnifiedAgent {

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
            String path = "c:/contract-analyze/sample-01/";
            String original = path + "original1.docx";
            String signed = path + "signed.pdf";
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