package edu.mcw.rgd;

import edu.mcw.rgd.datamodel.XdbId;
import edu.mcw.rgd.process.FileDownloader;

import java.util.List;

public class ImportPmcIds {

    public static void run(ReferenceUpdateDAO dao) throws Exception {

        int minRgdId = dao.getLastReferenceWithPmcId();

        System.out.println("STARTING REF_RGD_ID: "+minRgdId);

        List<Integer> refRgdIdsToProcess = dao.getActiveReferenceRgdIds(minRgdId);

        System.out.println("REFS TO PROCESS: "+refRgdIdsToProcess.size());

        int referencesProcessed = 0;
        int pmidsProcessed = 0;
        int pmcIdsInserted = 0;

        for( int refRgdId: refRgdIdsToProcess ) {

            referencesProcessed++;

            String pmid = dao.getPubMedIdForRefRgdId(refRgdId);
            if( pmid==null ) {
                continue;
            }
            pmidsProcessed++;

            String pmcId = getPmcId(pmid);
            if( pmcId!=null ) {

                XdbId xdbId = new XdbId();
                xdbId.setXdbKey(146); // PMC
                xdbId.setRgdId(refRgdId);
                xdbId.setAccId(pmcId);
                xdbId.setSrcPipeline("REFUPDATE");

                dao.insertXdbId(xdbId);
                pmcIdsInserted++;
            }

            System.out.println(referencesProcessed+".    pmids = "+pmidsProcessed+",  pmc ids = "+pmcIdsInserted);
            Thread.sleep(500);
        }

        System.out.println("REFERENCES PROCESSED: "+referencesProcessed);
        System.out.println("PUBMED IDS PROCESSED: "+pmidsProcessed);
        System.out.println("PMC IDS INSERTED: "+pmcIdsInserted);
    }

    static String getPmcId(String pmid) throws Exception {

        String url = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/elink.fcgi?dbfrom=pubmed&db=pmc&linkname=pubmed_pmc&id=";
        FileDownloader fd = new FileDownloader();
        fd.setExternalFile(url+pmid);
        String xml = fd.download();

        // valid response should contain string ''
        int idPos = xml.indexOf("<DbFrom>pubmed</DbFrom>");
        if( idPos<0 ) {
            throw new Exception("unexpected response:\n\n"+xml);
        }

        int pmcIndex = xml.indexOf("<LinkName>pubmed_pmc</LinkName>");
        if( pmcIndex<0 ) {
            return null; // no PMC attached to this reference
        }

        // there is PMC ID there!
        idPos = xml.indexOf("<Id>", pmcIndex);
        if( idPos>0 ) {
            idPos += 4;
            int idEnd = xml.indexOf("</Id>", idPos);
            String pmcId = xml.substring(idPos, idEnd);
            return "PMC"+pmcId;
        }
        System.out.println("unexpected");
        return null;
    }
}
