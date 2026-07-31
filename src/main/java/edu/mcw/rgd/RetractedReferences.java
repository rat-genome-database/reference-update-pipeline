package edu.mcw.rgd;

import edu.mcw.rgd.datamodel.Reference;
import edu.mcw.rgd.datamodel.ontology.Annotation;
import edu.mcw.rgd.process.FileDownloader2;
import edu.mcw.rgd.process.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Loads the published list of retracted papers into REFERENCES_RETRACTED and reports which RGD
 * references are affected. This module is read only as far as curated data goes: it never
 * withdraws a reference, edits a title or deletes an annotation -- REFERENCES_RETRACTED is the
 * only table it writes to, and it is refreshed in full on every run.
 *
 * NLM does not publish a standalone file of retracted publications: retractions are only available
 * as publication types inside the full PubMed baseline, or through an E-utilities query that caps
 * out at 9,999 records (there are far more retractions than that). This module therefore uses the
 * Retraction Watch database, which Crossref publishes as a single free CSV, carries the PubMed id
 * of both the retracted paper and its retraction notice, and additionally states the nature and
 * the reason of every retraction -- neither of which PubMed provides in a structured form.
 *
 * The nature matters: the file also carries corrections, expressions of concern and reinstatements,
 * and those must not be treated as retractions. Only rows whose nature is given by the
 * 'retractionNature' property count as a retraction for reporting purposes; the rest are still
 * loaded into REFERENCES_RETRACTED so that curators can see them.
 *
 * The source url and the CSV column names are bean properties, so that a change on the Crossref
 * side can be handled by editing AppConfigure.xml instead of rebuilding the pipeline.
 */
public class RetractedReferences {

    private static final Logger log = LogManager.getLogger("retracted_references");
    private static final Logger logWithdrawn = LogManager.getLogger("retracted_refs_withdrawn");
    private static final Logger logActive = LogManager.getLogger("retracted_refs_active");
    private static final Logger logDeleted = LogManager.getLogger("retracted_annotations");
    private static final Logger logStudies = LogManager.getLogger("retracted_studies");

    private String retractionWatchUrl;
    private String localFile;
    private int maxRetryCount;
    private int downloadRetryInterval;
    private Map<String, String> columns;
    private int topReasonCount;
    private String retractionNature;
    private List<String> retractionDateFormats;
    private String titlePrefix;
    private boolean dryRun;

    // FULL_ANNOT.ASPECT to the ontology its terms come from; read from the database, not configured
    private Map<String, String> aspectOntologies = new HashMap<>();

    public void run(ReferenceUpdateDAO dao) throws Exception {

        String downloadedFile = downloadRetractionData();

        List<Retraction> retractions = parse(downloadedFile);
        log.info("RETRACTION RECORDS WITH A PUBMED ID: " + retractions.size());

        Map<String, RefInRgd> refsByPmid = dao.getReferencesByPubmedId();
        log.info("RGD REFERENCES WITH A PUBMED ID: " + refsByPmid.size());

        aspectOntologies = dao.getAspectOntologies();
        log.debug("ASPECT TO ONTOLOGY MAPPINGS: " + aspectOntologies.size());

        // link each retraction to the RGD reference for the same PubMed id, if we have one
        List<Retraction> retracted = new ArrayList<>();
        for( Retraction r: retractions ) {
            RefInRgd ref = refsByPmid.get(r.originalPmid);
            if( ref!=null ) {
                r.rgdId = ref.rgdId;
                r.dateCreatedInRgd = ref.createdDate;
                r.objectStatus = ref.objectStatus;
                r.title = ref.title;
                // RGD_IDS has no dedicated 'date withdrawn' column, so the last modification of the
                // rgd id is the closest thing to when the reference was withdrawn
                if( ref.isWithdrawn() ) {
                    r.dateRetractedInRgd = ref.lastModifiedDate;
                }
            }
            if( isRetraction(r) && r.rgdId!=null ) {
                retracted.add(r);
            }
        }

        sync(dao, retractions);

        reportMatchesByNature(retractions);
        report(dao, retracted);
        reportStudies(dao, retracted);
        retract(dao, retracted);

        log.info("===");
    }

    /**
     * brings REFERENCES_RETRACTED in line with the download: rows new to it are inserted, rows
     * whose content changed are updated, and rows no longer in the download are deleted. Keyed on
     * the Retraction Watch record id, because nothing else in the row is unique.
     */
    void sync(ReferenceUpdateDAO dao, List<Retraction> retractions) throws Exception {

        Map<Long, Retraction> incoming = new HashMap<>();
        int duplicateRecordIds = 0;
        for( Retraction r: retractions ) {
            if( incoming.put(r.recordId, r) != null ) {
                duplicateRecordIds++;
            }
        }
        if( duplicateRecordIds > 0 ) {
            log.warn("record id is not unique in the downloaded file: " + duplicateRecordIds
                    + " duplicate(s); only the last row of each was kept");
        }

        Map<Long, Retraction> inRgd = dao.getRetractedReferences();
        log.info("REFERENCES_RETRACTED ROWS BEFORE SYNC: " + inRgd.size());

        List<Retraction> toInsert = new ArrayList<>();
        List<Retraction> toUpdate = new ArrayList<>();
        int unchanged = 0;

        for( Retraction r: incoming.values() ) {
            Retraction old = inRgd.get(r.recordId);
            if( old==null ) {
                toInsert.add(r);
            } else if( r.sameAs(old) ) {
                unchanged++;
            } else {
                toUpdate.add(r);
            }
        }

        List<Long> toDelete = new ArrayList<>();
        for( Long recordId: inRgd.keySet() ) {
            if( !incoming.containsKey(recordId) ) {
                toDelete.add(recordId);
            }
        }

        log.info("REFERENCES_RETRACTED INSERTED: " + dao.insertRetractedReferences(toInsert));
        log.info("REFERENCES_RETRACTED UPDATED: " + dao.updateRetractedReferences(toUpdate));
        log.info("REFERENCES_RETRACTED DELETED: " + dao.deleteRetractedReferences(toDelete));
        log.info("REFERENCES_RETRACTED UP TO DATE: " + unchanged);
    }

    /** a run-over of what matched RGD, so that non-retraction natures are not silently ignored */
    void reportMatchesByNature(List<Retraction> retractions) {

        Map<String, Integer> byNature = new TreeMap<>();
        for( Retraction r: retractions ) {
            if( r.rgdId!=null ) {
                byNature.merge(r.nature, 1, Integer::sum);
            }
        }
        log.info("MATCHED TO AN RGD REFERENCE, BY NATURE:");
        byNature.forEach((nature, count) ->
                log.info("   " + count + "  " + nature
                        + (nature.equalsIgnoreCase(getRetractionNature()) ? "   <== reported below" : "")));
    }

    void report(ReferenceUpdateDAO dao, List<Retraction> retracted) throws Exception {

        List<Integer> refRgdIds = new ArrayList<>();
        for( Retraction r: retracted ) {
            refRgdIds.add(r.rgdId);
        }
        Map<Integer, AnnotStats> annotStats = dao.getAnnotationStats(refRgdIds);
        for( Retraction r: retracted ) {
            r.annots = annotStats.getOrDefault(r.rgdId, EMPTY_STATS);
        }

        // the references worth a curator's time first: most annotations at stake, and among
        // equally annotated ones the most recently retracted
        retracted.sort((a, b) -> {
            if( a.annots.total != b.annots.total ) {
                return b.annots.total - a.annots.total;
            }
            long ta = a.retractionDate==null ? Long.MIN_VALUE : a.retractionDate.getTime();
            long tb = b.retractionDate==null ? Long.MIN_VALUE : b.retractionDate.getTime();
            return Long.compare(tb, ta);
        });

        int withdrawnWithAnnots = 0;
        int activeCount = 0;
        int activeAnnots = 0;
        Map<String, Integer> activeByAspect = new TreeMap<>();
        Map<String, Integer> activeBySource = new TreeMap<>();

        logWithdrawn.info("=== RETRACTED REFERENCES ALREADY WITHDRAWN IN RGD, THAT STILL HAVE ANNOTATIONS ===");
        logActive.info("=== RETRACTED REFERENCES STILL ACTIVE IN RGD ===");

        for( Retraction r: retracted ) {
            if( RefInRgd.WITHDRAWN.equals(r.objectStatus) ) {
                // report 1: withdrawn in RGD but the annotations are still there
                if( r.annots.total > 0 ) {
                    withdrawnWithAnnots++;
                    logWithdrawn.info(describe(r));
                }
            } else if( RefInRgd.ACTIVE.equals(r.objectStatus) ) {
                // report 2: retracted upstream, still active here
                activeCount++;
                activeAnnots += r.annots.total;
                r.annots.byAspect.forEach((k, v) -> activeByAspect.merge(k, v, Integer::sum));
                r.annots.bySource.forEach((k, v) -> activeBySource.merge(k, v, Integer::sum));
                logActive.info(describe(r));
            }
        }

        logWithdrawn.info("--- references withdrawn in RGD that still have annotations: " + withdrawnWithAnnots);
        logActive.info("--- references still active in RGD: " + activeCount
                + ", carrying " + activeAnnots + " annotations");
        logActive.info("--- annotations by ontology: " + formatAspects(activeByAspect));
        logActive.info("--- annotations by data source: " + formatCounts(activeBySource));

        log.info("RETRACTED, ALREADY WITHDRAWN IN RGD, STILL ANNOTATED: " + withdrawnWithAnnots);
        log.info("RETRACTED, STILL ACTIVE IN RGD: " + activeCount);
        log.info("ANNOTATIONS ON REFERENCES STILL ACTIVE IN RGD: " + activeAnnots);
        log.info("   by ontology:    " + formatAspects(activeByAspect));
        log.info("   by data source: " + formatCounts(activeBySource));
    }

    /**
     * PhenoMiner and gene expression studies built on a retracted paper. Reported only: the
     * experiment records keep their curation status until that side is implemented.
     */
    void reportStudies(ReferenceUpdateDAO dao, List<Retraction> retracted) throws Exception {

        List<Integer> refRgdIds = new ArrayList<>();
        for( Retraction r: retracted ) {
            refRgdIds.add(r.rgdId);
        }
        List<StudyInRgd> studies = dao.getStudiesForReferences(refRgdIds);

        log.info("STUDIES BUILT ON A RETRACTED REFERENCE: " + studies.size());
        if( studies.isEmpty() ) {
            return;
        }

        logStudies.info("=== STUDIES BUILT ON A RETRACTED REFERENCE ===");
        logStudies.info("These need a curator's attention: the experiment records behind them are NOT");
        logStudies.info("touched by this pipeline, so their curation status is unchanged.");
        for( StudyInRgd s: studies ) {
            logStudies.info("STUDY:" + s.studyId + "  RGD:" + s.refRgdId
                    + "  phenominer records:" + s.phenominerRecords
                    + "  expression records:" + s.expressionRecords
                    + "  type:" + s.studyType
                    + (s.dataType.isEmpty() ? "" : "  data type:" + s.dataType)
                    + "\n      " + s.studyName);
        }
        logStudies.info("--- studies needing review: " + studies.size());
        log.warn("STUDIES BUILT ON A RETRACTED REFERENCE NEED CURATOR REVIEW: " + studies.size());
    }

    /**
     * withdraws each retracted reference, marks its title, and moves its annotations out of
     * FULL_ANNOT into FULL_ANNOT_RETRACTED. Annotations are moved whatever the reference's status,
     * because a reference withdrawn by hand can still be carrying them.
     */
    void retract(ReferenceUpdateDAO dao, List<Retraction> retracted) throws Exception {

        if( isDryRun() ) {
            log.warn("DRY RUN: nothing was withdrawn, retitled or moved."
                    + " Set 'dryRun' to false on the retractedReferences bean to apply the changes.");
        }

        int annotsMoved = 0;
        int refsWithdrawn = 0;
        int titlesMarked = 0;

        for( Retraction r: retracted ) {

            List<AnnotInfo> annots = dao.getAnnotationsByReference(r.rgdId);
            if( !annots.isEmpty() ) {
                List<Integer> keys = new ArrayList<>();
                for( AnnotInfo a: annots ) {
                    keys.add(a.key);
                    logDeleted.info("RGD:" + r.rgdId + "  PMID:" + r.originalPmid
                            + "  annot_key:" + a.key
                            + "  " + a.termAcc + " [" + a.term + "]"
                            + "  aspect:" + a.aspect
                            + "  object:" + a.objectSymbol + " (RGD:" + a.annotatedObjectRgdId + ")"
                            + "  evidence:" + a.evidence
                            + "  src:" + a.dataSrc);
                }

                if( !isDryRun() ) {
                    int archived = dao.archiveAnnotations(keys);
                    int deleted = dao.deleteAnnotations(keys);
                    if( archived != deleted ) {
                        log.warn("RGD:" + r.rgdId + ": archived " + archived + " annotations but deleted "
                                + deleted + "; the difference was already in FULL_ANNOT_RETRACTED");
                    }
                }
                annotsMoved += keys.size();
            }

            // a reference withdrawn earlier, by a curator or a previous run, is left as it is
            if( RefInRgd.ACTIVE.equals(r.objectStatus) ) {
                Reference ref = dao.getReference(r.rgdId);
                if( ref==null ) {
                    log.warn("RGD:" + r.rgdId + " has no reference record; not withdrawn");
                    continue;
                }

                String title = Utils.defaultString(ref.getTitle());
                if( !title.startsWith(getTitlePrefix()) ) {
                    if( !isDryRun() ) {
                        ref.setTitle(getTitlePrefix() + title);
                        dao.updateReferenceField(ref);
                    }
                    titlesMarked++;
                }

                if( !isDryRun() ) {
                    dao.withdrawReference(ref);
                }
                refsWithdrawn++;
                logWithdrawn.info("WITHDRAWN RGD:" + r.rgdId + "  PMID:" + r.originalPmid
                        + "  annotations moved:" + annots.size());
            }
        }

        log.info((isDryRun() ? "WOULD BE " : "") + "ANNOTATIONS MOVED TO FULL_ANNOT_RETRACTED: " + annotsMoved);
        log.info((isDryRun() ? "WOULD BE " : "") + "REFERENCES WITHDRAWN: " + refsWithdrawn);
        log.info((isDryRun() ? "WOULD BE " : "") + "TITLES MARKED '" + getTitlePrefix() + "': " + titlesMarked);
    }

    String describe(Retraction r) {
        StringBuilder buf = new StringBuilder();
        buf.append("RGD:").append(r.rgdId)
                .append("  PMID:").append(r.originalPmid)
                .append("  retracted:").append(r.retractionDate==null ? "?" : new SimpleDateFormat("yyyy-MM-dd").format(r.retractionDate))
                .append("  notice PMID:").append(Utils.isStringEmpty(r.retractionPmid) ? "n/a" : r.retractionPmid)
                .append("\n      title:  ").append(r.title)
                .append("\n      reason: ").append(r.reason)
                .append("\n      annotations:").append(r.annots.total);
        if( r.annots.total > 0 ) {
            buf.append("\n          ontologies: ").append(formatAspects(r.annots.byAspect));
            buf.append("\n          sources:    ").append(formatCounts(r.annots.bySource));
        }
        return buf.toString();
    }

    /** 'aspect D (RDO)=8, aspect P (BP)=3' -- aspect, the ontology it belongs to, and the count */
    String formatAspects(Map<String, Integer> byAspect) {
        StringBuilder buf = new StringBuilder();
        byAspect.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> {
                    String ontId = getAspectOntologies()==null ? null : getAspectOntologies().get(e.getKey());
                    if( ontId==null ) {
                        log.warn("aspect '" + e.getKey() + "' is not tied to an ontology in the ONTOLOGIES table");
                        ontId = "?";
                    }
                    if( buf.length()>0 ) {
                        buf.append(", ");
                    }
                    buf.append("aspect ").append(e.getKey())
                            .append(" (").append(ontId).append(")=").append(e.getValue());
                });
        return buf.toString();
    }

    static String formatCounts(Map<String, Integer> counts) {
        StringBuilder buf = new StringBuilder();
        counts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> {
                    if( buf.length()>0 ) {
                        buf.append(", ");
                    }
                    buf.append(e.getKey()).append("=").append(e.getValue());
                });
        return buf.toString();
    }

    boolean isRetraction(Retraction r) {
        return r.nature!=null && r.nature.equalsIgnoreCase(getRetractionNature());
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
     * every row that carries a PubMed id for the retracted paper; rows without one cannot be
     * matched against RGD references and are skipped
     */
    List<Retraction> parse(String fileName) throws Exception {

        List<Retraction> retractions = new ArrayList<>();
        Map<String, Integer> natureCounts = new LinkedHashMap<>();
        Map<String, Integer> reasonCounts = new LinkedHashMap<>();
        int rows = 0;
        int rowsWithoutPmid = 0;
        int unparsableDates = 0;

        BufferedReader in = new BufferedReader(new FileReader(fileName));
        try {
            List<String> header = readRecord(in);
            if( header==null ) {
                throw new Exception("retraction file is empty: " + fileName);
            }
            int colRecordId = columnIndex(header, "recordId");
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

                String originalPmid = pmidOrEmpty(field(rec, colOriginalPmid));
                if( originalPmid.isEmpty() ) {
                    rowsWithoutPmid++;
                    continue;
                }

                String recordId = field(rec, colRecordId).trim();
                if( !recordId.matches("[0-9]+") ) {
                    log.warn("skipped a row whose record id is not numeric: [" + recordId + "]");
                    continue;
                }

                Retraction r = new Retraction();
                r.recordId = Long.parseLong(recordId);
                r.originalPmid = originalPmid;
                r.retractionPmid = pmidOrEmpty(field(rec, colRetractionPmid));
                r.nature = field(rec, colNature).trim();
                r.reason = normalizeReasons(field(rec, colReason));

                String rawDate = field(rec, colRetractionDate).trim();
                r.retractionDate = parseDate(rawDate);
                if( r.retractionDate==null && !rawDate.isEmpty() ) {
                    unparsableDates++;
                }

                natureCounts.merge(r.nature, 1, Integer::sum);
                for( String reason: r.reason.split("; ") ) {
                    if( !reason.isEmpty() ) {
                        reasonCounts.merge(reason, 1, Integer::sum);
                    }
                }
                retractions.add(r);
            }
        } finally {
            in.close();
        }

        log.info("RETRACTION RECORDS READ: " + rows + "  (" + rowsWithoutPmid + " without a PubMed id)");
        if( unparsableDates > 0 ) {
            log.warn("retraction dates that could not be parsed: " + unparsableDates);
        }
        logCounts("RETRACTION NATURE", natureCounts, 0);
        logCounts("TOP RETRACTION REASONS", reasonCounts, getTopReasonCount());
        return retractions;
    }

    /** dates look like '10/16/2025 0:00'; the exact patterns are configurable */
    Date parseDate(String s) {
        if( Utils.isStringEmpty(s) ) {
            return null;
        }
        for( String pattern: getRetractionDateFormats() ) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(pattern);
                sdf.setLenient(false);
                return sdf.parse(s);
            } catch( Exception ignored ) {
                // try the next pattern
            }
        }
        return null;
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

    static final AnnotStats EMPTY_STATS = new AnnotStats();

    /** one row of REFERENCES_RETRACTED */
    public static class Retraction {
        public long recordId;
        public String originalPmid;
        public String retractionPmid;
        public Date retractionDate;
        public String nature;
        public String reason;
        public Integer rgdId;
        public Date dateCreatedInRgd;
        public Date dateRetractedInRgd;

        // not stored, used for reporting only
        String objectStatus;
        String title;
        AnnotStats annots = EMPTY_STATS;

        /** true when every stored column matches, so the row does not need updating */
        boolean sameAs(Retraction other) {
            return Utils.stringsAreEqual(originalPmid, other.originalPmid)
                    && Utils.stringsAreEqual(retractionPmid, other.retractionPmid)
                    && sameDay(retractionDate, other.retractionDate)
                    && Utils.stringsAreEqual(nature, other.nature)
                    && Utils.stringsAreEqual(reason, other.reason)
                    && Objects.equals(rgdId, other.rgdId)
                    && sameDay(dateCreatedInRgd, other.dateCreatedInRgd)
                    && sameDay(dateRetractedInRgd, other.dateRetractedInRgd);
        }

        /** the columns are Oracle DATEs, so only the day is significant */
        static boolean sameDay(Date d1, Date d2) {
            if( d1==null || d2==null ) {
                return d1==null && d2==null;
            }
            SimpleDateFormat day = new SimpleDateFormat("yyyyMMdd");
            return day.format(d1).equals(day.format(d2));
        }
    }

    /** one annotation about to be moved out of FULL_ANNOT, with enough detail for the curator log */
    public static class AnnotInfo {
        public int key;
        public String termAcc;
        public String term;
        public String aspect;
        public String objectSymbol;
        public int annotatedObjectRgdId;
        public String evidence;
        public String dataSrc;
    }

    /** a PhenoMiner or gene expression study built on a retracted reference */
    public static class StudyInRgd {
        public int studyId;
        public int refRgdId;
        public String studyName;
        public String studyType;
        public String dataType;
        public int phenominerRecords;
        public int expressionRecords;
    }

    /** the annotations a reference carries, broken down for the report */
    public static class AnnotStats {
        public int total;
        public Map<String, Integer> byAspect = new TreeMap<>();
        public Map<String, Integer> bySource = new TreeMap<>();
    }

    /** an RGD reference, as far as this module is concerned */
    public static class RefInRgd {
        public static final String ACTIVE = "ACTIVE";
        public static final String WITHDRAWN = "WITHDRAWN";

        public int rgdId;
        public String objectStatus;
        public Date createdDate;
        public Date lastModifiedDate;
        public String title;

        boolean isWithdrawn() {
            return WITHDRAWN.equals(objectStatus);
        }
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

    public void setRetractionNature(String retractionNature) {
        this.retractionNature = retractionNature;
    }

    public String getRetractionNature() {
        return retractionNature;
    }

    public void setRetractionDateFormats(List<String> retractionDateFormats) {
        this.retractionDateFormats = retractionDateFormats;
    }

    public List<String> getRetractionDateFormats() {
        return retractionDateFormats;
    }

    public Map<String, String> getAspectOntologies() {
        return aspectOntologies;
    }

    public void setTitlePrefix(String titlePrefix) {
        this.titlePrefix = titlePrefix;
    }

    public String getTitlePrefix() {
        return titlePrefix;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public boolean isDryRun() {
        return dryRun;
    }
}
