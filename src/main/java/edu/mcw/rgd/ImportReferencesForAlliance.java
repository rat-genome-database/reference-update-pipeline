package edu.mcw.rgd;

import edu.mcw.rgd.dao.impl.AnnotationDAO;
import edu.mcw.rgd.datamodel.ontology.Annotation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.InetAddress;
import java.util.*;

public class ImportReferencesForAlliance {

    private String importPubMedUrl;
    private Map<String,String> importPubmedToolHost;

    private static final Logger logStatus = LogManager.getLogger("status");
    void run() throws Exception {

        int refRgdId = 8699517;

        AnnotationDAO adao = new AnnotationDAO();
        List<Annotation> annotations = adao.getAnnotationsByReference(refRgdId);
        Set<String> pmids = new HashSet<>();
        for( Annotation a: annotations ) {

            if( a.getXrefSource()!=null ) {
                String[] xrefs = a.getXrefSource().split("\\|");
                for( String xref: xrefs ) {
                    if (xref.startsWith("PMID:")) {
                        pmids.add( xref.substring(5));
                    }
                }
            }
        }
        importPubMedEntries(pmids);
    }

    int importPubMedEntries( Set<String> pubMedIdsToImport ) throws Exception {

        logStatus.info("===");
        logStatus.info("Importing missing references for HPO annotations.");
        logStatus.info("  PubMed ids in WITH_INFO fields of HPO annotations: " + pubMedIdsToImport.size());

        InetAddress inetAddress = InetAddress. getLocalHost();
        String hostName = inetAddress. getHostName().toLowerCase();
        String pubmedToolHostUrl = getImportPubmedToolHost().get(hostName);
        if( pubmedToolHostUrl==null ) {
            pubmedToolHostUrl = getImportPubmedToolHost().get("default");
        }
        if( pubmedToolHostUrl==null ) {
            throw new Exception("Update properties file and provide mapping for host "+hostName);
        }

        String importUrl = pubmedToolHostUrl+getImportPubMedUrl();

        logStatus.info("  Import URL: "+importUrl);

        int referencesImported = ImportReferencesForHtpAlliance.importMissingReferences(pubMedIdsToImport, importUrl);

        logStatus.info("  References for PubMed ids imported: "+referencesImported);
        return referencesImported;
    }

    public String getImportPubMedUrl() {
        return importPubMedUrl;
    }

    public void setImportPubMedUrl(String importPubMedUrl) {
        this.importPubMedUrl = importPubMedUrl;
    }

    public Map<String, String> getImportPubmedToolHost() {
        return importPubmedToolHost;
    }

    public void setImportPubmedToolHost(Map<String, String> importPubmedToolHost) {
        this.importPubmedToolHost = importPubmedToolHost;
    }
}
