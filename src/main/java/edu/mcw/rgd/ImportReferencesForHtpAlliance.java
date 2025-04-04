package edu.mcw.rgd;

import edu.mcw.rgd.process.FileDownloader;
import edu.mcw.rgd.process.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.*;

public class ImportReferencesForHtpAlliance {

    private static final Logger logStatus = LogManager.getLogger("status");

    public static void main( String[] args ) throws IOException {

        String fname = "/data/pipelines/ftp-file-extracts-pipeline/data/agr/HTPDATASET_RGD.json";
        fname = "/git/ftp-file-extracts-pipeline/src/main/dist/data/agr/HTPDATASET_RGD.json";

        String pubmedImportUrl = "https://dev.rgd.mcw.edu/rgdweb/pubmed/importReferences.html?pmid_list=";

        ImportReferencesForHtpAlliance instance = new ImportReferencesForHtpAlliance();
        instance.run(fname, pubmedImportUrl);
    }

    public void run( String htpDataSetFileName, String pubmedImportUrl ) throws IOException {

        BufferedReader in = Utils.openReader(htpDataSetFileName);
        String line;

        Set<String> incomingPubMedIds = new HashSet<>();

        while( (line=in.readLine())!=null ) {

            // extract PMID id from string
            // "publicationId" : "PMID:xxx"
            String pattern = "\"publicationId\" : \"PMID:";
            int pos1 = line.indexOf(pattern);
            if( pos1>0 ) {
                int pmidPosStart = pos1 + pattern.length();
                int pmidPosEnd = line.indexOf('\"', pmidPosStart);
                String pmid = line.substring(pmidPosStart, pmidPosEnd);
                incomingPubMedIds.add(pmid);
            }
        }
        in.close();

        logStatus.info("PubMed ids in HTPDATASET file: "+incomingPubMedIds.size());

        try {
            int referencesImported = importMissingReferences(incomingPubMedIds, pubmedImportUrl);

            logStatus.info("references has been imported: "+referencesImported);
        } catch( Exception e ) {
            e.printStackTrace();
        }
    }

    public static int importMissingReferences( Set<String> pmidSet, String pubmedImportUrl ) throws Exception {

        List<String> pmids = new ArrayList<>(pmidSet);
        Collections.shuffle(pmids);

        ReferenceUpdateDAO dao = new ReferenceUpdateDAO();

        FileDownloader fd = new FileDownloader();

        int referencesImported = 0;

        int i = 0;
        for( String pmid: pmids ) {

            i++;

            int refRgdId = dao.getReferenceRgdIdByPubmedId(pmid);

            if( refRgdId==0 ) {

                fd.setExternalFile(pubmedImportUrl+pmid);
                String response = fd.download();
                logStatus.debug("  "+i+"/"+pmids.size()+".  response: "+response);

                referencesImported++;
            }
        }

        return referencesImported;
    }
}
