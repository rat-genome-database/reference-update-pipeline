package edu.mcw.rgd;
import edu.mcw.rgd.datamodel.Reference;
import edu.mcw.rgd.datamodel.XdbId;
import edu.mcw.rgd.process.CounterPool;
import edu.mcw.rgd.process.FileDownloader;
import edu.mcw.rgd.process.NcbiEutils;
import edu.mcw.rgd.process.Utils;
import edu.mcw.rgd.xml.XomAnalyzer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.FileSystemResource;

import java.io.File;
import java.io.FileReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;

/*
* This pipeline does two things:
* <ol>
*   <li>fixes duplicate rgd ids for each reference
*   <li>imports missing references for PubMed ids created within last few days (as specified in pipeline params)
* </ol>
*/
public class ReferenceUpdatePipeline{

    ReferenceUpdateDAO dao = new ReferenceUpdateDAO();
    private String eUtils_db;
    private String eUtils_tool;
    private String eUtils_email;
    private String ncbiFetchUrl;
    private String eutils_rettype;
    private String eUtils_retmode;
    private String termSearchUrl;
    private String attR;
    private String version;

    private int pubmedImportDepthInDays;
    private String pubmedImportUrl;
    private int eutilsBatchSize;
    private Map<String,String> pubmedServices;
    private int pubmedImportBatchSize;

    private String htpFileName;

    private static final Logger logStatus = LogManager.getLogger("status");
    private static final Logger logImported = LogManager.getLogger("imported_references");

    public static void main(String args[]) throws Exception{

        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        new XmlBeanDefinitionReader(bf).loadBeanDefinitions(new FileSystemResource("properties/AppConfigure.xml"));
        ReferenceUpdatePipeline pipeline = (ReferenceUpdatePipeline) (bf.getBean("referenceUpdatePipeline"));

        if( args.length==0 ) {
            pipeline.usage();
        }

        long time0 = System.currentTimeMillis();

        boolean fixDuplicateReferences = false;
        boolean importMissingReferences = false;
        boolean refreshReferences = false;
        boolean fixDuplicateAuthors = false;
        boolean importPmcIds = false;
        boolean importReferencesForAllianceHtp = false;

        for( String arg: args ) {
            switch (arg) {
                case "-?", "-help" -> pipeline.usage();
                case "-fixDuplicateReferences" -> fixDuplicateReferences = true;
                case "-importMissingReferences" -> importMissingReferences = true;
                case "-refreshReferences" -> refreshReferences = true;
                case "-fixDuplicateAuthors" -> fixDuplicateAuthors = true;
                case "--importPmcIds" -> importPmcIds = true;
                case "--importReferencesForAllianceHtp" -> importReferencesForAllianceHtp = true;
            }
        }

        try {

            // run module 1
            if( fixDuplicateReferences ) {
                pipeline.run();
            }

            // run module 2
            if( importMissingReferences ) {
                // import references (if needed) for PMID ids that were created in RGD in the last few days
                pipeline.importReferences();
            }

            // run module 3
            if( refreshReferences ) {
                // refresh all active references in RGD by comparing content in RGD with content at PubMed
                pipeline.refreshReferences();
            }

            // run module 4
            if( fixDuplicateAuthors ) {
                pipeline.fixDuplicateAuthors();
            }

            if( importPmcIds ) {
                ImportPmcIds.run(pipeline.dao);
            }

            if( importReferencesForAllianceHtp ) {
                ImportReferencesForHtpAlliance module = new ImportReferencesForHtpAlliance();
                module.run( pipeline.getHtpFileName(), pipeline.getFullPubMedImportUrl() );
            }

        } catch (Exception e) {
            Utils.printStackTrace(e, logStatus);
            throw e;
        }

        pipeline.logMsg("=== PIPELINE FINISHED === elapsed "+Utils.formatElapsedTime(time0, System.currentTimeMillis()));
    }

    void usage() {
        System.out.println(getVersion());
        System.out.println("Usage: ");
        System.out.println(" java -jar ReferenceUpdatePipeline.jar <options>");
        System.out.println(" where options could be any combinations of the following:");
        System.out.println("   -?     prints this message and quit the program");
        System.out.println("   -help  prints this message and quit the program");
        System.out.println("   -fixDuplicateReferences");
        System.out.println("   -importMissingReferences");
        System.out.println("   -refreshReferences");
        System.out.println("   -fixDuplicateAuthors");
        System.out.println("   --importPmcIds");
        System.out.println("   --importReferencesForAllianceHtp");
        System.exit(0);
    }

    private File runReferenceUpdatePipeline(List<String> pubmedIds) throws Exception{

        // sometimes the xml file to be downloaded is partial
        // therefore we attempt 3 times to download it
        for( int attempt=1; attempt<=3; attempt++ ) {
            File xmlFile = downloadAndValidateFile(pubmedIds);
            if( xmlFile==null ) {
                logStatus.warn("Failed to download a xml file with "+pubmedIds.size()+" references. Retrying (attempt "+attempt+")");
            }
            else
                return xmlFile;
        }
        return null;
    }

    private File downloadAndValidateFile(List<String> pubmedIds) throws Exception{
        // download files through Ncbi eUtils
        String accIds= Utils.concatenate(pubmedIds, ",");
        NcbiEutils ncbiObj = new NcbiEutils();
        String url = getNcbiFetchUrl() + "?db=" + geteUtils_db() +
                "&tool=" + geteUtils_tool() +
                "&rettype=" + getEutils_rettype() +
                "&retmode=" + geteUtils_retmode() +
                "&id=" + accIds;
        //url.append("&id=").append("9495256,9021148,7579898,9096123,9250867,9716657,10967130,10708516,1914521,10920234,
        // 8698854,9674652,11152369,8662223,9468514,10920234,8698857,10613842,10330993,10331026,10331022,10331017,10331008,
        // 10331006,10331005,10330994,1033099");

        File xmlfile = ncbiObj.downloadFile(url);
        //System.out.println("File downloaded is: " + xmlfile);
        if(xmlfile.exists()) {
            // the just-downloaded xml file could be partial
            // if it is not partial, it is not a well formed xml, so it can't be processed
            return verifyXmlFile(xmlfile) ? xmlfile : null;
        }else{
            return null;
        }
    }

    // return true if the xml file is a well-formed xml file
    private boolean verifyXmlFile(File xmlFile) {

        try {
            XomAnalyzer analyzer = new XomAnalyzer();
            analyzer.parse(xmlFile);
            return true; // well formed xml
        }
        catch(Exception e) {
            logStatus.warn("partial file "+xmlFile.getName());
            return false; // malformed xml
        }
    }

    public void run() throws Exception {
        String msg = "Starting "+getVersion();
        logMsg(msg);

        int moreThanOnePubmedCount = 0;
        List<Reference> refList = dao.getActiveReferences(); //list of all reference objects
        List<String> pubmedIdList = new ArrayList<>(); //list of all pubmed ids that have just one ref rgdId.
        ReferenceUpdateObject refUpdObj = new ReferenceUpdateObject(dao);

        Map<String,Integer> pubmedIdToRefRgdIdMap = new HashMap<>();
        CounterPool counters = new CounterPool();

        int countPubmedIdsInserted=0;

        for(Reference refObj:refList){
            counters.increment("refCount");
            List<XdbId> xdbObjList = dao.getXdbIdsByRgdId(XdbId.XDB_KEY_PUBMED, refObj.getRgdId());

            if(xdbObjList!=null){
                for(XdbId x : xdbObjList){
                    counters.increment("pubmedCount");
                    Integer refRgdId = pubmedIdToRefRgdIdMap.get(x.getAccId());
                    if( refRgdId!=null && refRgdId!=x.getRgdId() ) {
                        moreThanOnePubmedCount++;

                        msg = "pubmed id "+x.getAccId()+" is mapped to multiple ref_rgd_ids: " + refRgdId + ", "+x.getRgdId();
                        logMsg(msg);
                    }
                    else {
                        pubmedIdToRefRgdIdMap.put(x.getAccId(), x.getRgdId());
                        pubmedIdList.add(x.getAccId());
                    }
                }
            } else {
                if( Utils.stringsAreEqual(refObj.getReferenceType(), "JOURNAL ARTICLE")
                        && !Utils.NVL(refObj.getRefAbstract(), "NULL").equals("NULL")
                        && !Utils.NVL(refObj.getTitle(), "NULL").equals("NULL") ) {

                    int pubmedIdInserted = refUpdObj.getPubmedAssocInDB(refObj, getTermSearchUrl(), getAttR());
                    if(pubmedIdInserted!=0){
                        countPubmedIdsInserted++;
                    }
                }
            }
        }

        msg = "number of pubmedIds inserted:" + countPubmedIdsInserted;
        logMsg(msg);

        msg = "Count of objects which have more than one Pubmed ID for every ReferenceObject: " + moreThanOnePubmedCount;
        logMsg(msg);

        downloadAndProcessReferences(pubmedIdList, pubmedIdToRefRgdIdMap, counters);

        msg = "number of references: " + counters.get("refCount");
        logMsg(msg);

        msg = "number of PubmedIds: " + counters.get("pubmedCount");
        logMsg(msg);

        msg = "number of PubmedReferencesDownloaded: " + counters.get("pubmedIdsDownloaded");
        msg += " (details in updates.log)";
        logMsg(msg);

        msg = "number of DOIs: " + counters.get("doiCount");
        logMsg(msg);
    }

    void logMsg(String msg) {
        System.out.println(msg);
        logStatus.info(msg);
    }

    void downloadAndProcessReferences(List<String> pubmedIdList, Map<String,Integer> pubmedIdToRefRgdIdMap, CounterPool counters) throws Exception {

        for(int x=0; x<pubmedIdList.size(); x+=getEutilsBatchSize()){

            List<String> xdbListSubset = null;
            if((x+getEutilsBatchSize())<=pubmedIdList.size()){
                xdbListSubset = pubmedIdList.subList(x, x + getEutilsBatchSize());
            }else if(pubmedIdList.size()<(x+getEutilsBatchSize())){
                xdbListSubset = pubmedIdList.subList(x, pubmedIdList.size());
            }

            File xmlFile = runReferenceUpdatePipeline(xdbListSubset);
            if(xmlFile != null){
                parseXMLfile(xmlFile, pubmedIdToRefRgdIdMap, counters);
            }

            Thread.sleep(5000); // sleep 5s between consequitive requests
        }
    }

    private void parseXMLfile(File xmlFile, Map<String, Integer> pubmedIdToRefRgdIdMap, CounterPool counters) throws Exception {

        FileReader reader = new FileReader(xmlFile);
        //creating new instance of the XOMAnalyser parser that does the parsing of each node(corresponding to each rs ID) in the xml file
        ReferenceXOMAnalyzer xomFile = new ReferenceXOMAnalyzer(pubmedIdToRefRgdIdMap, counters);
        //starting process for each "node" in the chromosome xml file.
        xomFile.parse(reader);

        reader.close();
        xmlFile.delete();
    }

    /**
     * check all PUBMED ids created within last few days; import references for them if needed
     */
    void importReferences() throws Exception {

        logMsg("=== IMPORTING MISSING REFERENCES for PubMed ids created within last " + getPubmedImportDepthInDays() + " days");
        long time0 = System.currentTimeMillis();

        String importPubmedUrl = getFullPubMedImportUrl();
        logMsg("  import pubmed url: "+importPubmedUrl);

        // create cutoff date for PUBMED ids created recently
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(new Date());
        calendar.add(Calendar.DATE, -getPubmedImportDepthInDays());
        Date cutOffDate = calendar.getTime();

        List<String> pubMedIds = dao.getPubmedIdsWithoutReferenceInRgd(cutOffDate);
        logMsg("  pubmed ids without reference in RGD: "+pubMedIds.size());

        int failedDownloads = 0;
        final int BATCH_SIZE = getPubmedImportBatchSize();
        for( int i=0; i<pubMedIds.size(); i+=BATCH_SIZE ) {
            int endIndex = i + BATCH_SIZE;
            if( endIndex>pubMedIds.size() ) {
                endIndex=pubMedIds.size();
            }
            failedDownloads += importReferences(importPubmedUrl, pubMedIds.subList(i, endIndex), false);

            // sleep for 5 sec before issuing a next request
            Thread.sleep(5000);
        }

        if( failedDownloads>0 ) {
            logMsg("  WARN: count of failed reference downloads: " + failedDownloads);
        }
        logMsg("=== IMPORTED MISSING REFERENCES === ELAPSED "+Utils.formatElapsedTime(time0, System.currentTimeMillis()));
    }

    /**
     * refresh all active references in RGD by comparing content in RGD with content at PubMed
     */
    void refreshReferences() throws Exception {

        logMsg("=== REFRESHING REFERENCES in RGD against PubMed");
        long time0 = System.currentTimeMillis();

        String importPubmedUrl = getFullPubMedImportUrl();
        logMsg("  import pubmed url: "+importPubmedUrl);

        List<String> pubMedIds = dao.getPubmedIdsForActiveReferences();
        Collections.shuffle(pubMedIds);
        logMsg("  pubmed ids with reference in RGD: "+pubMedIds.size());

        for( int i=0; i<pubMedIds.size(); i++ ) {
            importReferences(importPubmedUrl, pubMedIds.subList(i, i+1), true);
        }

        logMsg("=== REFRESHED REFERENCES === ELAPSED "+Utils.formatElapsedTime(time0, System.currentTimeMillis()));
    }

    int importReferences(String importPubmedUrl, List<String> pubMedIdList, boolean updateMode) throws Exception {

        String pubMedIds = Utils.concatenate(pubMedIdList, ",");
        logImported.info("downloading PMID: "+pubMedIds);

        FileDownloader downloader = new FileDownloader();
        if( updateMode ) {
            downloader.setExternalFile(importPubmedUrl + pubMedIds + "&mode=update");
            downloader.setLocalFile("data/pmid_"+pubMedIdList.get(0)+".txt");
        } else {
            downloader.setExternalFile(importPubmedUrl + pubMedIds);
            downloader.setLocalFile("data/pmid_"+pubMedIdList.get(0)+".html");
        }
        downloader.setPrependDateStamp(true);

        // default retry count is 8, so we could wait up to an hour until download fails (or completes)
        // that's too much: we want to fail fast
        downloader.setMaxRetryCount(2);

        try {
            String localFile = downloader.download();

            // determine nr of failed references
            String fileContent = Utils.readFileAsString(localFile);

            String pattern1 = "Total References Failed : ";
            int pos1 = fileContent.indexOf(pattern1);
            if( pos1>0 ) {
                pos1 += pattern1.length();
                int pos2 = fileContent.indexOf("<p>", pos1);
                if( pos2>0 ) {
                    int failedCount = Integer.parseInt(fileContent.substring(pos1, pos2).trim());
                    return failedCount;
                }
            }
        } catch(FileDownloader.PermanentDownloadErrorException e) {
        }
        return pubMedIdList.size();
    }

    public void fixDuplicateAuthors() throws Exception {
        logMsg("---FIX DUPLICATE AUTHORS: START");
        dao.fixDuplicateAuthors();
        logMsg("---FIX DUPLICATE AUTHORS: END");
    }

    public String getFullPubMedImportUrl() throws UnknownHostException {

        String hostName = InetAddress.getLocalHost().getHostName().toLowerCase(); // f.e. travis.rgd.mcw.edu
        String serverName = hostName.split("[\\.]")[0];
        String importPubmedUrl = getPubmedServices().get(serverName) + getPubmedImportUrl();
        return importPubmedUrl;
    }

    public void seteUtils_db(String eUtils_db) {
        this.eUtils_db = eUtils_db;
    }

    public String geteUtils_db() {
        return eUtils_db;
    }

    public void seteUtils_tool(String eUtils_tool) {
        this.eUtils_tool = eUtils_tool;
    }

    public String geteUtils_tool() {
        return eUtils_tool;
    }

    public void seteUtils_email(String eUtils_email) {
        this.eUtils_email = eUtils_email;
    }

    public String geteUtils_email() {
        return eUtils_email;
    }

    public void setNcbiFetchUrl(String ncbiFetchUrl) {
        this.ncbiFetchUrl = ncbiFetchUrl;
    }

    public String getNcbiFetchUrl() {
        return ncbiFetchUrl;
    }

    public void setEutils_rettype(String eutils_rettype) {
        this.eutils_rettype = eutils_rettype;
    }

    public String getEutils_rettype() {
        return eutils_rettype;
    }

    public void seteUtils_retmode(String eUtils_retmode) {
        this.eUtils_retmode = eUtils_retmode;
    }

    public String geteUtils_retmode() {
        return eUtils_retmode;
    }


    public void setTermSearchUrl(String termSearchUrl) {
        this.termSearchUrl = termSearchUrl;
    }

    public String getTermSearchUrl() {
        return termSearchUrl;
    }

    public void setAttR(String attR) {
        this.attR = attR;
    }

    public String getAttR() {
        return attR;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getVersion() {
        return version;
    }

    public void setPubmedImportDepthInDays(int pubmedImportDepthInDays) {
        this.pubmedImportDepthInDays = pubmedImportDepthInDays;
    }

    public int getPubmedImportDepthInDays() {
        return pubmedImportDepthInDays;
    }

    public void setPubmedImportUrl(String pubmedImportUrl) {
        this.pubmedImportUrl = pubmedImportUrl;
    }

    public String getPubmedImportUrl() {
        return pubmedImportUrl;
    }

    public void setEutilsBatchSize(int eutilsBatchSize) {
        this.eutilsBatchSize = eutilsBatchSize;
    }

    public int getEutilsBatchSize() {
        return eutilsBatchSize;
    }

    public void setPubmedServices(Map<String,String> pubmedServices) {
        this.pubmedServices = pubmedServices;
    }

    public Map<String, String> getPubmedServices() {
        return pubmedServices;
    }

    public void setPubmedImportBatchSize(int pubmedImportBatchSize) {
        this.pubmedImportBatchSize = pubmedImportBatchSize;
    }

    public int getPubmedImportBatchSize() {
        return pubmedImportBatchSize;
    }

    public String getHtpFileName() {
        return htpFileName;
    }

    public void setHtpFileName(String htpFileName) {
        this.htpFileName = htpFileName;
    }
}


