package common;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.ser.blueline.*;
import com.ser.blueline.agents.IAgentDefinition;
import com.ser.blueline.agents.IAgentJob;
import com.ser.blueline.agents.IAgentJobQueryResult;
import com.ser.blueline.bpm.IBpmService;
import com.ser.blueline.bpm.IProcessInstance;
import com.ser.blueline.bpm.IProcessType;
import com.ser.blueline.bpm.ITask;
import com.ser.blueline.metaDataComponents.IArchiveClass;
import com.ser.blueline.metaDataComponents.IArchiveFolderClass;
import com.ser.blueline.metaDataComponents.IObjectClassBase;
import com.ser.blueline.metaDataComponents.IStringMatrix;
import com.ser.foldermanager.*;
import de.ser.doxis4.agentserver.UnifiedAgent;
import jakarta.xml.bind.DatatypeConverter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GeneralLib {
    public static Logger log = null;
    public static ISession session;
    public static IDocumentServer server;
    public static IBpmService bpm;
    public static Map<String, Object> params;
    public static ProcessHelper processHelper;
    public static List<JSONObject> schemes;
    public static List<JSONObject> jobs;
    public static String tempPath = System.getProperty("java.io.tmpdir");

    public static IAgentJob agentJob = null;
    public static IAgentDefinition agentDefinition = null;
    public static class Patterns {
        public static String defaultDate = "dd.MM.yyyy";
        public static String defaultTime = "HH:mm:ss";
        public static String defaultDateTime = "dd.MM.yyyy HH:mm:ss";
        public static List<String> dateTime = new ArrayList<>(Arrays.asList(
                "yyyyMMdd", "yyyy/MM/dd", "dd/MM/yyyy", "MM/dd/yyyy",
                "yyyy.MM.dd", "dd.MM.yyyy", "MM.dd.yyyy",
                "yyyy-MM-dd", "dd-MM-yyyy", "MM-dd-yyyy",
                "yyyy.MM.dd HH:mm:ss", "dd.MM.yyyy HH:mm:ss", "MM.dd.yyyy HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss", "dd-MM-yyyy HH:mm:ss", "MM-dd-yyyy HH:mm:ss",
                "yy","yyyy","M","MM","d","dd","H","HH","m","mm","s","ss"
        ));
    }
    public static void load(UnifiedAgent agent, boolean noLoadAgent) throws Exception {
        log = LogManager.getLogger(agent.getClass().getName());

        session = agent.getSes();
        server = GeneralLib.session.getDocumentServer();
        bpm = agent.getBpm();
        params = agent.getParams();
        processHelper = new ProcessHelper(session);

        if(!noLoadAgent) {
            agentJob = GeneralLib.getAgentJob();
            if (agentJob == null) {
                throw new Exception("Agent Job not found.");
            }

            String agentDefnId = agentJob.getAgentDefinitionID();
            if (agentDefnId == null || agentDefnId.isBlank()) {
                throw new Exception("Agent Defn. ID not found.");
            }
            agentDefinition = GeneralLib.server.getAgentDefinition(GeneralLib.session, agentDefnId);
            if (agentDefinition == null) {
                throw new Exception("Agent Definition not found.");
            }
            log = LogManager.getLogger(agentDefinition.getName());
        }

        if(log == null){
            log = LogManager.getLogger(agent.getClass().getName() + "__General");
        }
    }
    public static String md5String(String text) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(text.getBytes());
        byte[] digest = md.digest();
        return DatatypeConverter.printHexBinary(digest).toUpperCase();
    }

    public static Path downloadFile2(URL url, String path, String fileName) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) (new URL(url + "")).openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");

        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            return null;
        }

        Path target = Paths.get(path, fileName);

        try (InputStream in = connection.getInputStream();
             OutputStream out = new FileOutputStream(target.toFile())) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            return target;
        }
    }
    public static File downloadFile(URL url, String path, String fileName) throws Exception {
        HttpResponse<InputStream> response = Unirest.get(url + "")
                .asBinary();

        if (response.getStatus() != 200) {return null;}

        File rtrn = Paths.get(path, fileName).toFile();

        try (InputStream inputStream = response.getBody();
             FileOutputStream outputStream = new FileOutputStream(rtrn)) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        }

        return rtrn;
    }
    public static String parseOneLine(String text) {
        String rtrn = text+"";
        rtrn = rtrn.replace("\"", "\\\"");
        JSONObject rjsn = new JSONObject("{\"text\":\"" + rtrn + "\"}");
        rtrn = rjsn.getString("text");
        return rtrn;
    }
    public static String loadDirectory(String path) {
        (new File(path)).mkdir();
        return path;
    }
    public static boolean updateDocumentLink(IInformationObject infoObj) throws Exception {
        boolean rtrn = false;
        IInformationObject folder = infoObj.getPrimaryParent();
        if(folder != null){
            if(disconnectFromNode((IFolder) folder,infoObj,null,null)){
                rtrn = true;
            }
            if(connectNodeWithClsId((IFolder) folder, infoObj, null, null, null)) {
                rtrn = true;
            }
        }
        return rtrn;
    }
    public static boolean removeFromNode(IFolder folder, String rootName, String nodeName, IDocument pdoc) throws Exception {
        boolean rtrn = false;
        List<INode> nodesByName = folder.getNodesByName(rootName);
        INode iNode = nodesByName.get(0);
        INodes root = (INodes) iNode.getChildNodes();
        INode newNode = root.getItemByName(nodeName);
        if(newNode != null) {
            IElements elements = newNode.getElements();
            while(elements.getCount2() > 0) {
                IElement element = elements.getItem2(0);
                elements.remove(0);
            }
            folder.commit();
            rtrn = true;
        }
        return rtrn;
    }
    public static String exportDocumentDocx(IDocument document, String exportPath, String fileName) throws IOException {
        String rtrn ="";
        for(int representationConter = 0; representationConter < document.getRepresentationCount(); ++representationConter) {
            for (int partDocumentCounter = 0; partDocumentCounter < document.getPartDocumentCount(representationConter); ++partDocumentCounter) {
                IDocumentPart partDocument = document.getPartDocument(representationConter, partDocumentCounter);
                //log.info("MIME TYPE ::" + partDocument.getMimeType());
                if(!Objects.equals(partDocument.getMimeType(), "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) continue;
                String fName = (!fileName.isEmpty() ? fileName : partDocument.getFilename());
                fName = fName.replaceAll("[\\\\/:*?\"<>|]", "_");

                try (InputStream inputStream = partDocument.getRawDataAsStream()) {
                    IFDE fde = partDocument.getFDE();
                    if (fde.getFDEType() == IFDE.FILE) {
                        rtrn = exportPath + "/" + fName + "." + ((IFileFDE) fde).getShortFormatDescription();
                        try (FileOutputStream fileOutputStream = new FileOutputStream(rtrn)){
                            byte[] bytes = new byte[2048];
                            int length;
                            while ((length = inputStream.read(bytes)) > -1) {
                                fileOutputStream.write(bytes, 0, length);
                            }
                        }
                    }
                }
            }
        }
        return rtrn;
    }
    public static String exportDocumentPDF(IDocument document, String exportPath, String fileName) throws IOException {
        String rtrn ="";
        for(int representationConter = 0; representationConter < document.getRepresentationCount(); ++representationConter) {
            for (int partDocumentCounter = 0; partDocumentCounter < document.getPartDocumentCount(representationConter); ++partDocumentCounter) {
                IDocumentPart partDocument = document.getPartDocument(representationConter, partDocumentCounter);
                //log.info("MIME TYPE ::" + partDocument.getMimeType());
                if(!Objects.equals(partDocument.getMimeType(), "application/pdf")) continue;
                String fName = (!fileName.isEmpty() ? fileName : partDocument.getFilename());
                fName = fName.replaceAll("[\\\\/:*?\"<>|]", "_");

                try (InputStream inputStream = partDocument.getRawDataAsStream()) {
                    IFDE fde = partDocument.getFDE();
                    if (fde.getFDEType() == IFDE.FILE) {
                        rtrn = exportPath + "/" + fName + "." + ((IFileFDE) fde).getShortFormatDescription();
                        try (FileOutputStream fileOutputStream = new FileOutputStream(rtrn)){
                            byte[] bytes = new byte[2048];
                            int length;
                            while ((length = inputStream.read(bytes)) > -1) {
                                fileOutputStream.write(bytes, 0, length);
                            }
                        }
                    }
                }
            }
        }
        return rtrn;
    }
    public static String exportDocument(IDocument document, String exportPath, String fileName) throws IOException {
        String rtrn ="";
        IDocumentPart partDocument = document.getPartDocument(document.getDefaultRepresentation() , 0);

        String fName = (!fileName.isEmpty() ? fileName : partDocument.getFilename());
        fName = fName.replaceAll("[\\\\/:*?\"<>|]", "_");

        try (InputStream inputStream = partDocument.getRawDataAsStream()) {
            IFDE fde = partDocument.getFDE();
            if (fde.getFDEType() == IFDE.FILE) {
                rtrn = exportPath + "/" + fName + "." + ((IFileFDE) fde).getShortFormatDescription();

                try (FileOutputStream fileOutputStream = new FileOutputStream(rtrn)){
                    byte[] bytes = new byte[2048];
                    int length;
                    while ((length = inputStream.read(bytes)) > -1) {
                        fileOutputStream.write(bytes, 0, length);
                    }
                }
            }
        }
        return rtrn;
    }
    public static boolean addToNode(IFolder folder, IDocument pdoc, String nodeName) throws Exception {
        GeneralLib._info("Add2Node Start");
        boolean add2Node = false;
        String className = pdoc.getArchiveClass().getName();
        GeneralLib._info("Add2Node....ClassName : " + className);

        INode custDocNode = null;
        int nodesCount = folder.getNodes2().getCount();
        List<INode> nodesByName = folder.getNodesByName(nodeName);
        for(INode node : nodesByName){
            if(node.getType() == FMNodeType.STATIC){
                custDocNode = node;
                break;
            }
        }
        if(custDocNode == null){
            throw new Exception("Node is null. AddToNote exeption." + nodeName);
        }
        custDocNode.refresh(true);
        boolean isExistElement = false;
        GeneralLib._info("Start ProjectDoc exit in folder: " + isExistElement);
        IElements nelements = custDocNode.getElements();
        for(int i=0;i<nelements.getCount2();i++) {
            IElement nelement = nelements.getItem2(i);
            String edocID = nelement.getLink();
            String pdocID = pdoc.getID();
            if(Objects.equals(pdocID, edocID)){
                isExistElement = true;
                break;
            }
        }
        GeneralLib._info("Finish Add2Node exit in folder: " + isExistElement);
        if(!isExistElement) {
            add2Node = folder.addInformationObjectToNode(pdoc.getID(), custDocNode.getID());
            GeneralLib._info("Add2Node add to folder: " + custDocNode.getID());
            if (add2Node) {
                GeneralLib._info("Add2Node setting new node ID: " + custDocNode.getID());
            }
            folder.commit();
        }
        return add2Node;
    }
    public static IDocument getDocFromFolderNode(IFolder folder, String nodeName) throws Exception {
        IDocument rtrn = null;
        //log.info("Get Output From Node.... Start");
        INode custDocNode = null;
        List<INode> nodesByName = folder.getNodesByName(nodeName);
        for(INode node : nodesByName){
            if(node.getType() == FMNodeType.STATIC){
                custDocNode = node;
                break;
            }
        }
        if(custDocNode == null){
            throw new Exception("Node is null. Get From Node exeption." + nodeName);
        }
        custDocNode.refresh(true);
        IElements nelements = custDocNode.getElements();
        for(int i=0;i<nelements.getCount2();i++) {
            IElement nelement = nelements.getItem2(i);
            String edocID = nelement.getLink();
            IInformationObject edoc = server.getInformationObjectByID(edocID,session);
            if(edoc != null){
                rtrn = (IDocument) edoc;
                rtrn = server.getDocumentCurrentVersion(session,rtrn.getID());
                //log.info("Found Doc " + rtrn.getDisplayName() + " in NodeName:" + nodeName + " in folder");
                break;
            }
        }
        return rtrn;
    }
    public static boolean disconnectFromNode(IFolder folder, IInformationObject infoObj, String uuid, INode node){
        boolean rtrn = false;
        INodes nods = (node == null ? folder.getNodes2() : ((INodes) node.getChildNodes()));
        String id = (uuid != null ? uuid : infoObj.getDocumentID().getUniqueKey());

        for(int i=0;i<nods.getCount();i++) {
            INode fnod = nods.getItem(i);
            IElements nelements = fnod.getElements();
            for (int j = 0; j < nelements.getCount2(); j++) {
                IElement nelement = nelements.getItem2(j);
                if(nelement.getLink() == null){continue;}
                if(nelement.getLink().indexOf(id) < 0) {continue;}
                nelements.remove(j);
                rtrn = true;
                break;
            }
            if(disconnectFromNode(folder, infoObj, id, fnod)){
                rtrn = true;
            }
        }
        return rtrn;
    }
    public static boolean connectNodeWithClsId(IFolder folder, IInformationObject infoObj, String clsId, String uuid, INode node){
        boolean rtrn = false;
        String ioClsId = (clsId == null || clsId.isBlank() ? infoObj.getClassID() : clsId);
        INodes nods = (node == null ? folder.getNodes2() : ((INodes) node.getChildNodes()));
        String id = (uuid != null ? uuid : infoObj.getDocumentID().getUniqueKey());

        for(int i=0;i<nods.getCount();i++) {
            INode fnod = nods.getItem(i);
            List<IObjectClassBase> ocbs = fnod.resolveAllowedTargetClasses();
            for(IObjectClassBase ocb : ocbs){
                if(!ocb.getID().equals(ioClsId)) {continue;}
                boolean isex = false;
                IElements nelements = fnod.getElements();
                for (int j = 0; j < nelements.getCount2(); j++) {
                    IElement nelement = nelements.getItem2(j);
                    if(nelement.getLink() == null){continue;}
                    if(nelement.getLink().indexOf(id) > (-1)) {
                        isex = true;
                        break;
                    }
                }
                if (!isex) {
                    folder.addInformationObjectToNode(infoObj.getID(), fnod.getID());
                    rtrn = true;
                }
            }
            if(connectNodeWithClsId(folder, infoObj, clsId, id, fnod)){
                rtrn = true;
            }
        }
        return rtrn;
    }
    public static boolean disconnectNode(IFolder folder, IInformationObject infoObj, String uuid, INode node){
        boolean rtrn = false;


        ILink[] links2 = server.getReferencingRelationships(session, infoObj.getID(),false);

        /*
        INodes nods = (node == null ? folder.getNodes2() : ((INodes) node.getChildNodes()));
        //String id = (uuid != null ? uuid : infoObj.getDocumentID().getUniqueKey());
        String id = infoObj.getID();

        for(int i=0;i<nods.getCount();i++) {
            INode fnod = nods.getItem(i);
            if(disconnectNode(folder, infoObj, id, fnod)){
                rtrn = true;
            }

            IElements nelements = fnod.getElements();
            for (int j = 0; j < nelements.getCount2(); j++) {
                IElement nelement = nelements.getItem2(j);
                if(nelement.getLink() == null){continue;}
                if(nelement.getLink().indexOf(id) > (-1)) {
                    nelements.remove(i);
                    rtrn = true;
                }
            }
        }
        */
        return rtrn;
    }
    public static JSONObject getOptions(String cfgn, String keyf, String valf) throws Exception {
        List<JSONObject> cfgs = getAllConfigs(cfgn);
        JSONObject rtrn = new JSONObject("{}");
        for(JSONObject line : cfgs){
            if(!line.has(keyf)){continue;}
            if(line.get(keyf) == null || !(line.get(keyf) instanceof String) || line.getString(keyf).isBlank()){continue;}
            if(rtrn.has(keyf)){continue;}

            String vlue = "";
            if(line.has(valf)){
                vlue = line.getString(valf);
            }

            rtrn.put(line.getString(keyf), vlue);
        }
        return rtrn;
    }
    public static int parseInt(String str){
        try{
            return Integer.parseInt(str);
        } catch(Exception ex){
            return Integer.MIN_VALUE;
        }
    }
    public static JSONObject getSettings(String cfgn, String key) throws Exception {
        List<JSONObject> cfgs = getAllConfigs(cfgn);
        JSONObject rtrn = new JSONObject("{}");
        for(JSONObject line : cfgs){
            if(!line.has(key)){continue;}
            if(line.get(key) == null || !(line.get(key) instanceof String) || line.getString(key).isBlank()){continue;}
            if(rtrn.has(key)){continue;}
            rtrn.put(line.getString(key), line);
        }
        return rtrn;
    }

    public static List<JSONObject> getAllConfigs(String cfgn) throws Exception {
        List<JSONObject> rtrn = new ArrayList<>();

        IStringMatrix mtrx = server.getStringMatrix(cfgn, session);
        if(mtrx == null){throw new Exception(cfgn + " Global Value List not found");}

        List<String> cols = new ArrayList<>();
        for(int c=0;c<mtrx.getColumnCount();c++){
            String cnme = mtrx.getColumnName(c);
            cnme = (cnme.isBlank() ? "Column_" + c : cnme);
            int w = 0;
            while(cols.contains(cnme + (w > 0 ? "_" + w : ""))){w++;}
            cols.add(cnme + (w > 0 ? "_" + w : ""));
        }

        List<List<String>> rmtx = mtrx.getRawRows();

        for(List<String> xrow : rmtx) {
            JSONObject line = new JSONObject();
            int c=(-1);
            for(String cclm : cols){
                c++;
                line.put(cclm, xrow.size() > c ? xrow.get(c) : "");
            }
            rtrn.add(line);
        }
        return rtrn;
    }
    public static List<JSONObject> findJobs(List<JSONObject> plst) throws Exception {
        List<JSONObject> list = plst != null ? plst : new ArrayList<>();
        String adnm = agentDefinition.getName();
        GeneralLib._info("Agent-Definition : " + adnm);
        int s = 0;
        for(JSONObject rcll : jobs){
            s++;
            if(!rcll.has("agentDefinition")){continue;}
            if(!rcll.getString("agentDefinition").equals(adnm)){continue;}


            GeneralLib._info("Find-Job [" + adnm +  "]: " + rcll.toString());

            JSONObject nsch = new JSONObject(rcll.toString());
            nsch.put("order", s+"");
            list.add(nsch);
        }

        List<JSONObject> rtrn = new ArrayList<>();
        for(JSONObject rshm : list) {
            if (!rshm.has("order")) {
                continue;
            }
            String shnm = "scheme_" + rshm.getString("order");

            JSONObject rlin = new JSONObject();

            for (int i = 1; i < 100; i++) {
                String rkey = "call_" + i;
                if (!rshm.has(rkey)) {
                    break;
                }

                String rval = rshm.getString(rkey);
                rval = (rval == null || rval.isBlank() ? "{}" : rval);

                JSONObject rjsn = new JSONObject(rval);
                rjsn.put("name", shnm + "/" + rkey);
                rlin.put(i + "", rjsn);
            }
            rtrn.add(rlin);
        }
        return rtrn;
    }
    public static List<JSONObject> findInfoObjSchemes(IInformationObject infobj, List<JSONObject> plst) throws Exception {
        List<JSONObject> list = plst != null ? plst : new ArrayList<>();
        IDocumentID did = infobj.getDocumentID();
        String ddb = did.getDatabaseName();
        String dty = infobj.getClassID();
        int s = 0;
        String adnm = agentDefinition.getName();

        for(JSONObject schm : schemes){
            if(!schm.has("agentDefinition")){continue;}
            if(!schm.getString("agentDefinition").equals(adnm)){continue;}

            s++;
            if(!schm.has("dbs") && !schm.has("types") && !schm.has("checkQuery")){continue;}

            String tdbs = schm.getString("dbs");
            tdbs = (tdbs.isBlank() ? "" : tdbs);
            boolean itdbs = Arrays.asList("", "*", "@ALL").contains(tdbs.toUpperCase());


            String typs = schm.getString("types");
            typs = (typs.isBlank() ? "" : typs);
            boolean ityps = Arrays.asList("", "*", "@ALL").contains(typs.toUpperCase());

            String tquery = schm.getString("checkQuery");
            tquery = (tquery.isBlank() ? "" : tquery);
            boolean itquery = tquery.isBlank();

            if(!itdbs || !ityps || !itquery) {
                String[] cdbs = itdbs ? new String[]{ddb} : tdbs.split(";");
                if (!Arrays.asList(cdbs).contains(ddb)) {
                    continue;
                }
                String[] ctys = ityps ? new String[]{dty} : typs.split(";");
                if (!Arrays.asList(ctys).contains(dty)) {
                    continue;
                }
                String twhereClause = "TYPE IN ('" + String.join("', '", ctys) + "')"
                        + " AND UUID='" + did.getUniqueKey() + "'"
                        + (!tquery.isBlank() ? " AND " : "") + tquery;
                IInformationObject[] chks = processHelper.createQuery(new String[]{ddb}, twhereClause, "", 1, true);
                if (chks == null || chks.length == 0) {
                    continue;
                }
            }
            JSONObject nsch = new JSONObject(schm.toString());
            nsch.put("order", s+"");
            list.add(nsch);
        }
        List<JSONObject> rtrn = new ArrayList<>();
        for(JSONObject rshm : list){
            if(!rshm.has("order")){continue;}
            String shnm = "scheme_" + rshm.getString("order");

            JSONObject rlin = new JSONObject();

            for(int i=1;i<100;i++){
                String rkey = "call_" + i;
                if(!rshm.has(rkey)){break;}

                String rval = rshm.getString(rkey);
                rval = (rval == null || rval.isBlank() ? "{}" : autoText(rval, Arrays.asList(infobj)));

                JSONObject rjsn = new JSONObject(rval);
                rjsn.put("name", shnm + "/" + rkey);
                rlin.put(i + "", rjsn);
            }
            rtrn.add(rlin);
        }

        return rtrn;
    }

    public static void copyDescriptors(IInformationObject sour, IInformationObject targ) throws Exception {
        IValueDescriptor[] sdls = sour.getDescriptorList();
        for (IValueDescriptor sdvl : sdls) {
            if (!hasDescriptor(targ, sdvl.getDescriptor().getName())) {
                continue;
            }
            targ.setDescriptorValueTyped(sdvl.getDescriptor().getId(),
                    sour.getDescriptorValue(sdvl.getName())
            );
        }
    }
    public static String autoText(String str, List<Object> objs) throws Exception{
        StringBuffer rtr1 = new StringBuffer();
        String tmp = str + "";
        String spr = "/";
        JSONObject params = new JSONObject("{}");
        Pattern ptr1 = Pattern.compile( "\\{\\{([\\w\\s\\.\\:\\,\\$\\/\\[\\]]+)\\}\\}" );
        Matcher mtc1 = ptr1.matcher(tmp);
        while(mtc1.find()) {
            String mk = mtc1.group(1);
            String mv = "";
            boolean im = false;
            if(!im && params.has(mk)) {
                mv = params.getString(mk);
                mv = (mv == null ? "" : mv);
                im = true;
            }
            if(!im) {
                String mf = (mk.contains(spr) ? mk.substring(0, mk.indexOf(spr)) : mk);
                String mp = (mk.contains(spr) ? mk.substring(mk.indexOf(spr) + spr.length()) : "");
                mv = autoValue(objs, mf, mp);
                params.put(mk, mv);
                im = true;
            }

            if(!im){
                mv = "";
            }
            mtc1.appendReplacement(rtr1,  mv);
        }
        mtc1.appendTail(rtr1);
        tmp = rtr1.toString();

        return tmp;
    }
    public static IDescriptor getDescriptor(String dn) throws Exception {
        return server.getDescriptorForName(session, dn);
    }
    public static String autoValue(List<Object> objs, String pnam, String prp) throws Exception{
        IDescriptor d = getDescriptor(pnam);
        if(d == null){
            for(Object o : objs) {
                IInformationObject info = (IInformationObject) o;

                if (pnam.equals("$ClassId")) {
                    return info.getObjectClassBase().getID();
                }
                if (pnam.equals("$ClassName")) {
                    return info.getObjectClassBase().getName();
                }
            }
            return "";
        }

        int dtyp = d.getDescriptorType();

        for(Object o : objs){
            IInformationObject info = (IInformationObject) o;

            if(pnam.equals("$ClassName")){
                return info.getClass().getName();
            }

            if(!hasDescriptor(info, pnam)){continue;}

            if(dtyp == IDescriptor.TYPE_STRING) {
                String pvalString = info.getDescriptorValue(pnam, String.class);
                return (pvalString == null ? "" : pvalString);
            }
            if(dtyp == IDescriptor.TYPE_DOUBLE) {
                Double pvalDouble = info.getDescriptorValue(pnam, Double.class);
                return (pvalDouble == null ? "" : Double.toString(pvalDouble));
            }
            if(dtyp == IDescriptor.TYPE_INTEGER) {
                Integer pvalInteger = info.getDescriptorValue(pnam, Integer.class);
                return (pvalInteger == null ? "" : Integer.toString(pvalInteger));
            }
            if(dtyp == IDescriptor.TYPE_LONG) {
                Long pvalLong = info.getDescriptorValue(pnam, Long.class);
                return (pvalLong == null ? "" : Long.toString(pvalLong));
            }
            if(dtyp == IDescriptor.TYPE_BOOLEAN) {
                Boolean pvalBoolean = info.getDescriptorValue(pnam, Boolean.class);
                return (pvalBoolean == null ? "" : Boolean.toString(pvalBoolean));
            }
            if(dtyp == IDescriptor.TYPE_DATETIME || dtyp == IDescriptor.TYPE_DATE) {
                Date pvalDate = info.getDescriptorValue(pnam, Date.class);
                if(pvalDate != null) {
                    if(prp.isEmpty() && !Patterns.defaultDateTime.isEmpty()) {
                        String dtvlDefault = (new SimpleDateFormat(Patterns.defaultDateTime)).format(pvalDate);
                        return (dtvlDefault == null ? "" : dtvlDefault);
                    }
                    if(!prp.isEmpty() && Patterns.dateTime.contains(prp)){
                        String dtvlString = (new SimpleDateFormat(prp)).format(pvalDate);
                        return (dtvlString == null ? "" : dtvlString);
                    }
                }
            }

        }
        return "";
    }
    public static boolean hasDescriptor(IInformationObject object, String descName){
        IDescriptor[] descs = session.getDocumentServer().getDescriptorByName(descName, session);
        List<String> checkList = new ArrayList<>();
        for(IDescriptor ddsc : descs){
            checkList.add(ddsc.getId());
        }
        String[] descIds = new String[0];
        if(object instanceof IFolder){
            String classID = object.getClassID();
            IArchiveFolderClass folderClass = session.getDocumentServer().getArchiveFolderClass(classID , session);
            descIds = folderClass.getAssignedDescriptorIDs();
        }else if(object instanceof IDocument){
            IArchiveClass documentClass = ((IDocument) object).getArchiveClass();
            descIds = documentClass.getAssignedDescriptorIDs();
        }else if(object instanceof ITask){
            IProcessType processType = ((ITask) object).getProcessType();
            descIds = processType.getAssignedDescriptorIDs();
        }else if(object instanceof IProcessInstance){
            IProcessType processType = ((IProcessInstance) object).getProcessType();
            descIds = processType.getAssignedDescriptorIDs();
        }

        List<String> descList = Arrays.asList(descIds);
        for(String dId : descList){
            if(checkList.contains(dId)){return true;}
        }
        return false;
    }
    public static boolean isStartedParalelWithAgntDefns(){
        String ajbId = paramStringValue("agentJobId");
        if(ajbId == null || ajbId.isBlank()){return false;}

        IAgentJob ajob = server.getAgentJob(session, ajbId);
        if(ajob == null || ajob.getAgentDefinitionID().isBlank()){return false;}

        String sttm = String.join(" AND ",new String[]{
                "UUID<>'" + ajob.getID() + "'",
                "AGENT_DEFINITION_UUID='" + ajob.getAgentDefinitionID() + "'",
                "AGENT_JOB_STATUS='STARTED'"
        });
        //log.info("Query [isStartedParalelWithAgntDefns] statement." + sttm);

        IAgentJobQueryResult ares = server.createAgentJobQuery(session).setSearchStatement(sttm).query();
        //log.info("ARES.size :: " + (ares != null ? ares.getResults().size() : "-Null-"));

        if(ares == null || ares.getResults().isEmpty()){return false;}

        return true;

    }
    public static boolean isStartedParalelWithObjIds(String pObjId){
        String objId = pObjId != null && !pObjId.isBlank() ? pObjId : paramStringValue("AGENT_EVENT_OBJECT_CLIENT_ID");
        if(objId == null || objId.isBlank()){return false;}

        String ajbId = paramStringValue("agentJobId");
        if(ajbId == null || ajbId.isBlank()){return false;}

        String sttm = String.join(" AND ",new String[]{
                "UUID<>'" + ajbId + "'",
                "AGENT_EVENT_OBJECT_CLIENT_ID='" + objId + "'",
                "AGENT_JOB_STATUS='STARTED'"
        });
        //log.info("Query [isStartedParalelWithObjIds] statement." + sttm);

        IAgentJobQueryResult ares = server.createAgentJobQuery(session).setSearchStatement(sttm).query();
        //log.info("ARES.size :: " + (ares != null ? ares.getResults().size() : "-Null-"));

        if(ares == null || ares.getResults().isEmpty()){return false;}

        return true;
    }
    public static IAgentJob getAgentJob(){
        String ajbId = paramStringValue("agentJobId");
        if(ajbId == null || ajbId.isBlank()){return null;}

        String sttm = String.join(" AND ",new String[]{
                "UUID='" + ajbId + "'"
        });
        //log.info("Query [getAgentJob] statement." + sttm);

        IAgentJobQueryResult ares = server.createAgentJobQuery(session).setSearchStatement(sttm).query();
        //log.info("ARES.size :: " + (ares != null ? ares.getResults().size() : "-Null-"));

        if(ares == null || ares.getResults().isEmpty()){return null;}

        return ares.getResults().get(0);
    }
    public static String paramStringValue(String pkey){
        for (Map.Entry<String, Object> entry : params.entrySet()){
            if(!entry.getKey().equals(pkey)){continue;}
            if(entry.getValue() == null){continue;}
            if(!(entry.getValue() instanceof String)){continue;}
            return entry.getValue().toString();
        }
        return "";
    }

    public static IDocument newFileToDocumentClass(String filePath, String archiveClassID, String extension) throws Exception {
        IArchiveClass cls = GeneralLib.server.getArchiveClass(archiveClassID, GeneralLib.session);
        if (cls == null) cls = GeneralLib.server.getArchiveClassByName(GeneralLib.session, archiveClassID);
        if (cls == null) throw new Exception("Document Class: " + archiveClassID + " not found");
        extension = (extension != null ? extension : "pdf");
        String dbName = GeneralLib.session.getDatabase(cls.getDefaultDatabaseID()).getDatabaseName();
        IDocument doc = GeneralLib.server.getClassFactory().getDocumentInstance(dbName, cls.getID(), "0000", GeneralLib.session);

        File file = new File(filePath);
        IRepresentation representation = doc.addRepresentation("." + extension , "document");
        IDocumentPart newDocumentPart = representation.addPartDocument(filePath);
        //doc.commit();
        return doc;
    }
    public static File fileExists(String p) {
        File f = new File(p);
        if (!f.isFile()) {
            throw new IllegalArgumentException("File not found: " + p);
        }
        return f;
    }
    public static void _info(String txt){
        System.out.println("[INFO] " + txt);
        log.info(txt);
        writePipelineLog("INFO", txt);
    }
    public static void _warn(String txt){
        System.out.println("[WARN] " + txt);
        log.warn(txt);
        writePipelineLog("WARN", txt);
    }
    public static void _error(String txt){
        System.out.println("[ERROR] " + txt);
        log.error(txt);
        writePipelineLog("ERROR", txt);
    }

    // ------------------------------------------------------------------ pipeline log
    // Pipeline.run() başlarken set edilir, bitince null'a çekilir.
    public static volatile java.io.PrintWriter pipelineLog = null;

    private static void writePipelineLog(String level, String txt) {
        java.io.PrintWriter pw = pipelineLog;   // volatile read — null-safe
        if (pw == null) return;
        String ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new java.util.Date());
        pw.println(ts + " " + level + " GeneralLib - " + txt);
        pw.flush();
    }
}
