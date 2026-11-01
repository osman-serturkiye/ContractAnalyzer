package process;

import ai.Router;
import analyzer.AnalyzeHelper;
import com.ser.blueline.IDocument;
import com.ser.blueline.IInformationObject;
import com.ser.blueline.bpm.IProcessInstance;
import com.ser.blueline.bpm.ITask;
import com.ser.foldermanager.IFolder;
import config.AppConfig;
import de.ser.doxis4.agentserver.UnifiedAgent;

import common.GeneralLib;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;
import report.ReportBuilder;
import steps.Pipeline;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;



public class OriginalSignedAnalyze extends UnifiedAgent {

    @Override
    protected Object execute() {
        try{
            String agentJobID = "22331328-691b-4486-90c8-5ca10a900d7a";
            //String agentJobID = GeneralLib.paramStringValue("agentJobId");
            //String agentJobID = getAgentJobId();
            if(agentJobID == null || agentJobID.isBlank()){
                return resultError(
                        "AGENT JOB ID is NULL"
                );
            }
            GeneralLib.load(this, true);
            String path = GeneralLib.loadDirectory(GeneralLib.tempPath + agentJobID + "/");
            GeneralLib._info("Started");
            /*
            if(GeneralLib.isStartedParalelWithObjIds(null)){
                int rdly = (new Random()).nextInt(30) + 1;
                return resultRestart("Paralel Agent [isStartedParalelWithObjIds] ...", rdly);
            }
            */
            ITask eventTask = getEventTask();
            if (eventTask == null) {
                throw new Exception(
                        "OBJECT CLIENT ID is NULL or not of Type ITask"
                );
            }
            GeneralLib._info("1-Getting Process Instance");
            IProcessInstance processInstance = eventTask.getProcessInstance();
            String refId = processInstance.getDescriptorValue("ObjectNumberReference");
            if(refId == null){
                throw new Exception(
                        "REFERENCE ID is NULL"
                );
            }
            GeneralLib._info("2-Getting Reference Object for ID:" + refId);

            IInformationObject refInfo = GeneralLib.server.getInformationObjectByID(refId,GeneralLib.session);
            if(refInfo == null){
                throw new Exception(
                        "REFERENCE OBJECT is NULL for ID : " + refId
                );
            }
            GeneralLib._info("3-Approve Document get...");
            IDocument approvedDoc = GeneralLib.getDocFromFolderNode((IFolder) refInfo,"Approved Contract");
            if(approvedDoc == null){
                throw new Exception(
                        "Approved Document not found for ID : " + refId
                );
            }

            GeneralLib._info("3-Signed Document get...");
            IDocument signedDoc = GeneralLib.getDocFromFolderNode((IFolder) refInfo,"Signed Copy");
            if(signedDoc == null){
                throw new Exception(
                        "Signed Document not found for ID : " + refId
                );
            }

            String docxPath  = GeneralLib.exportDocumentDocx((IDocument) approvedDoc, path, "Approved_Document"); ///docx olmalı
            String signedPath  = GeneralLib.exportDocumentPDF((IDocument) signedDoc, path, "Signed_Document");///pdf olmalı (check et)

            GeneralLib._info("PATH-Approve Document..." + docxPath);
            GeneralLib._info("PATH-Signed Document..." + signedPath);

            JSONObject result = AnalyzeHelper.contractAnalyze(path,docxPath,signedPath);
            String reportHTML  = result.getString("reportHTML");
            String reportPDF_TR = result.getString("reportPDF_TR");
            String reportPDF_EN = result.getString("reportPDF_EN");
            GeneralLib._info("Result HTML path..." + reportHTML);
            GeneralLib._info("Result PDF TR path..." + reportPDF_TR);
            GeneralLib._info("Result PDF EN path..." + reportPDF_EN);

            String analyzeNode = "Contract Analyze";

            // Upload HTML report
            IDocument analyzeDoc = GeneralLib.getDocFromFolderNode((IFolder) refInfo, analyzeNode);
            if(analyzeDoc == null){
                analyzeDoc = GeneralLib.newFileToDocumentClass(reportHTML,"5e6ba933-9f0b-49c5-82b4-1759021a91c8","html");
                analyzeDoc.commit();
                GeneralLib._info("created analyze document.:" + analyzeDoc);
                boolean addToNode = GeneralLib.addToNode((IFolder) refInfo, analyzeDoc, analyzeNode);
                GeneralLib._info("Analyze document ("+analyzeDoc+") add to folder : " + addToNode);
            }

            /*
            IDocument analyzeDocPdfTr = GeneralLib.getDocFromFolderNode((IFolder) refInfo, analyzeNode);
            if(analyzeDocPdfTr == null){
                analyzeDocPdfTr = GeneralLib.newFileToDocumentClass(reportPDF_TR,"5e6ba933-9f0b-49c5-82b4-1759021a91c8","pdf");
                analyzeDocPdfTr.commit();
                GeneralLib._info("created analyze PDF TR document.:" + analyzeDocPdfTr);
                boolean addToNode = GeneralLib.addToNode((IFolder) refInfo, analyzeDocPdfTr, analyzeNode);
                GeneralLib._info("Analyze PDF TR document ("+analyzeDocPdfTr+") add to folder : " + addToNode);
            }

            IDocument analyzeDocPdfEn = GeneralLib.getDocFromFolderNode((IFolder) refInfo, analyzeNode);
            if(analyzeDocPdfEn == null){
                analyzeDocPdfEn = GeneralLib.newFileToDocumentClass(reportPDF_EN,"5e6ba933-9f0b-49c5-82b4-1759021a91c8","pdf");
                analyzeDocPdfEn.commit();
                GeneralLib._info("created analyze PDF EN document.:" + analyzeDocPdfEn);
                boolean addToNode = GeneralLib.addToNode((IFolder) refInfo, analyzeDocPdfEn, analyzeNode);
                GeneralLib._info("Analyze PDF EN document ("+analyzeDocPdfEn+") add to folder : " + addToNode);
            }
            */

            //processInstance.setDescriptorValue("ObjectName",reportHTML);
            //processInstance.commit();

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