package edu.mcw.rgd;

import edu.mcw.rgd.process.FileDownloader2;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Reports which active RGD references have been retracted.
 *
 * NLM does not publish a standalone file of retracted publications: retractions are only available
 * as publication types inside the full PubMed baseline, or through an E-utilities query that caps
 * out at 9,999 records (there are far more retractions than that). This module therefore uses the
 * Retraction Watch database, which Crossref publishes as a single free CSV, carries the PubMed id
 * of both the retracted paper and its retraction notice, and additionally states the reason for
 * every retraction -- something PubMed does not provide in a structured form at all.
 *
 * The source url and the CSV column names are bean properties, so that a change on the Crossref
 * side can be handled by editing AppConfigure.xml instead of rebuilding the pipeline.
 */
public class RetractedReferences {

    private static final Logger log = LogManager.getLogger("retracted_references");

    private String retractionWatchUrl;
    private String localFile;
    private int maxRetryCount;
    private int downloadRetryInterval;
    private Map<String, String> columns;
    private int topReasonCount;

    public void run(ReferenceUpdateDAO dao) throws Exception {

        String downloadedFile = downloadRetractionData();

        Map<String, Retraction> retractionsByPmid = parse(downloadedFile);
        log.info("RETRACTIONS WITH A PUBMED ID: " + retractionsByPmid.size());

        List<String> pmidsInRgd = dao.getPubmedIdsForActiveReferences();
        log.info("ACTIVE RGD REFERENCES WITH A PUBMED ID: " + pmidsInRgd.size());

        // report every active RGD reference that has been retracted
        int retractedInRgd = 0;
        for( String pmid: new TreeSet<>(pmidsInRgd) ) {
            Retraction r = retractionsByPmid.get(pmid);
            if( r==null ) {
                continue;
            }
            retractedInRgd++;

            int refRgdId = dao.getReferenceRgdIdByPubmedId(pmid);
            log.info("  RGD:" + refRgdId + "  PMID:" + pmid
                    + "  retracted " + r.retractionDate
                    + "  [" + r.nature + "]"
                    + "  notice PMID:" + (r.retractionPmid.isEmpty() ? "n/a" : r.retractionPmid)
                    + "  reason: " + r.reason);
        }

        log.info("RETRACTED RGD REFERENCES: " + retractedInRgd);
        log.info("===");
    }

    String downloadRetractionData() throws Exception {

        FileDownloader2 fd = new FileDownloader2();
        fd.setExternalFile(getRetractionWatchUrl());
        fd.setLocalFile(getLocalFile());
        fd.setPrependDateStamp(true);
        fd.setMaxRetryCount(getMaxRetryCount());
        fd.setDownloadRetryInterval(getDownloadRetryInterval());

        String downloadedFile = fd.downloadNew();
        log.info("DOWNLOADED: " + downloadedFile);
        return downloadedFile;
    }

    /**
     * builds a map of retracted-paper PubMed id to its retraction; rows without a PubMed id for the
     * original paper are counted but cannot be matched against RGD references, so they are skipped
     */
    Map<String, Retraction> parse(String fileName) throws Exception {

        Map<String, Retraction> retractions = new HashMap<>();
        Map<String, Integer> natureCounts = new LinkedHashMap<>();
        Map<String, Integer> reasonCounts = new LinkedHashMap<>();
        int rows = 0;
        int rowsWithoutPmid = 0;

        BufferedReader in = new BufferedReader(new FileReader(fileName));
        try {
            List<String> header = readRecord(in);
            if( header==null ) {
                throw new Exception("retraction file is empty: " + fileName);
            }
            int colOriginalPmid = columnIndex(header, "originalPmid");
            int colRetractionPmid = columnIndex(header, "retractionPmid");
            int colRetractionDate = columnIndex(header, "retractionDate");
            int colNature = columnIndex(header, "nature");
            int colReason = columnIndex(header, "reason");

            List<String> rec;
            while( (rec=readRecord(in))!=null ) {
                if( rec.size() < header.size() ) {
                    continue; // trailing blank line
                }
                rows++;

                Retraction r = new Retraction();
                r.retractionPmid = pmidOrEmpty(field(rec, colRetractionPmid));
                r.retractionDate = field(rec, colRetractionDate).trim();
                r.nature = field(rec, colNature).trim();
                r.reason = normalizeReasons(field(rec, colReason));

                natureCounts.merge(r.nature, 1, Integer::sum);
                for( String reason: r.reason.split("; ") ) {
                    if( !reason.isEmpty() ) {
                        reasonCounts.merge(reason, 1, Integer::sum);
                    }
                }

                String originalPmid = pmidOrEmpty(field(rec, colOriginalPmid));
                if( originalPmid.isEmpty() ) {
                    rowsWithoutPmid++;
                    continue;
                }
                // a paper can appear more than once (f.e. retracted, then reinstated); last row wins
                retractions.put(originalPmid, r);
            }
        } finally {
            in.close();
        }

        log.info("RETRACTION RECORDS READ: " + rows + "  (" + rowsWithoutPmid + " without a PubMed id)");
        logCounts("RETRACTION NATURE", natureCounts, 0);
        logCounts("TOP RETRACTION REASONS", reasonCounts, getTopReasonCount());
        return retractions;
    }

    static void logCounts(String title, Map<String, Integer> counts, int limit) {
        log.debug(title + ":");
        counts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(limit>0 ? limit : counts.size())
                .forEach(e -> log.debug("   " + e.getValue() + "  " + e.getKey()));
    }

    /** resolves a logical column name, via the configured mapping, to its position in the file header */
    int columnIndex(List<String> header, String logicalName) throws Exception {

        String columnName = getColumns()==null ? null : getColumns().get(logicalName);
        if( columnName==null ) {
            throw new Exception("no '" + logicalName + "' entry in the 'columns' property of the retractedReferences bean");
        }
        int i = header.indexOf(columnName);
        if( i<0 ) {
            throw new Exception("retraction file has no '" + columnName + "' column; format must have changed");
        }
        return i;
    }

    static String field(List<String> rec, int i) {
        return i>=0 && i<rec.size() ? rec.get(i) : "";
    }

    static String pmidOrEmpty(String s) {
        s = s.trim();
        if( s.isEmpty() || s.equals("0") ) {
            return "";
        }
        for( int i=0; i<s.length(); i++ ) {
            if( !Character.isDigit(s.charAt(i)) ) {
                return "";
            }
        }
        return s;
    }

    /** reasons come as '+Reason One;+Reason Two;' */
    static String normalizeReasons(String reasons) {
        StringBuilder buf = new StringBuilder();
        for( String reason: reasons.split(";") ) {
            reason = reason.trim();
            if( reason.startsWith("+") ) {
                reason = reason.substring(1).trim();
            }
            if( reason.isEmpty() ) {
                continue;
            }
            if( buf.length()>0 ) {
                buf.append("; ");
            }
            buf.append(reason);
        }
        return buf.toString();
    }

    /**
     * reads one CSV record; fields may be quoted, and a quoted field may contain commas,
     * doubled quotes and line breaks -- all of which occur in this file
     */
    static List<String> readRecord(BufferedReader in) throws Exception {

        List<String> fields = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        boolean inQuotes = false;
        boolean anyInput = false;
        int c;

        while( (c=in.read()) != -1 ) {
            anyInput = true;
            char ch = (char) c;

            if( inQuotes ) {
                if( ch=='"' ) {
                    in.mark(1);
                    int next = in.read();
                    if( next=='"' ) {
                        buf.append('"'); // an escaped quote
                    } else {
                        inQuotes = false;
                        if( next!=-1 ) {
                            in.reset();
                        }
                    }
                } else {
                    buf.append(ch);
                }
                continue;
            }

            if( ch=='"' ) {
                inQuotes = true;
            } else if( ch==',' ) {
                fields.add(buf.toString());
                buf.setLength(0);
            } else if( ch=='\n' ) {
                fields.add(buf.toString());
                return fields;
            } else if( ch!='\r' ) {
                buf.append(ch);
            }
        }

        if( !anyInput ) {
            return null;
        }
        fields.add(buf.toString());
        return fields;
    }

    static class Retraction {
        String retractionPmid;
        String retractionDate;
        String nature;
        String reason;
    }

    public void setRetractionWatchUrl(String retractionWatchUrl) {
        this.retractionWatchUrl = retractionWatchUrl;
    }

    public String getRetractionWatchUrl() {
        return retractionWatchUrl;
    }

    public void setLocalFile(String localFile) {
        this.localFile = localFile;
    }

    public String getLocalFile() {
        return localFile;
    }

    public void setMaxRetryCount(int maxRetryCount) {
        this.maxRetryCount = maxRetryCount;
    }

    public int getMaxRetryCount() {
        return maxRetryCount;
    }

    public void setDownloadRetryInterval(int downloadRetryInterval) {
        this.downloadRetryInterval = downloadRetryInterval;
    }

    public int getDownloadRetryInterval() {
        return downloadRetryInterval;
    }

    public void setColumns(Map<String, String> columns) {
        this.columns = columns;
    }

    public Map<String, String> getColumns() {
        return columns;
    }

    public void setTopReasonCount(int topReasonCount) {
        this.topReasonCount = topReasonCount;
    }

    public int getTopReasonCount() {
        return topReasonCount;
    }
}
