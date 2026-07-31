package edu.mcw.rgd;

import edu.mcw.rgd.dao.impl.AssociationDAO;
import edu.mcw.rgd.dao.impl.OntologyXDAO;
import edu.mcw.rgd.dao.impl.ReferenceDAO;
import edu.mcw.rgd.dao.impl.XdbIdDAO;
import edu.mcw.rgd.dao.spring.IntListQuery;
import edu.mcw.rgd.datamodel.Author;
import edu.mcw.rgd.datamodel.Reference;
import edu.mcw.rgd.datamodel.XdbId;
import edu.mcw.rgd.datamodel.ontologyx.Ontology;
import edu.mcw.rgd.process.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.*;

/**
 * @author pjayaraman
 * @since 2/27/12
 * wrapper for *all* dao code
 */
public class ReferenceUpdateDAO {

    XdbIdDAO xdbIdDao = new XdbIdDAO();
    ReferenceDAO refDao = new ReferenceDAO();
    AssociationDAO assDao = new AssociationDAO();
    OntologyXDAO ontDao = new OntologyXDAO();

    public List<Reference> getActiveReferences() throws Exception {

        return refDao.getActiveReferences();
    }

    public Reference getReference(int rgdId) throws Exception{
        return refDao.getReferenceByRgdId(rgdId);
    }

    public void updateReferenceField(Reference ref) throws Exception {
        refDao.updateReference(ref);
    }

    /**
     * return external ids for given xdb key and rgd-id
     * @param xdbKey - external database key (like 3 for EntrezGene)
     * @param rgdId - rgd-id
     * @return list of external ids
     */
    public List<XdbId> getXdbIdsByRgdId(int xdbKey, int rgdId) throws Exception {
        List<XdbId> listXdbs = xdbIdDao.getXdbIdsByRgdId(xdbKey, rgdId);
        if( listXdbs.size()>0 ) {
            return listXdbs;
        }else{
            return null;
        }
    }

    public List<Author> getAuthorsListFromDB(int referenceKey)throws Exception{
        List<Author> authorsList = refDao.getAuthors(referenceKey);
        if(authorsList!=null && authorsList.size()>0){
            return authorsList;
        }else{
            return null;
        }
    }

    public List<String> getPubmedIdsWithoutReferenceInRgd(Date cutoffDate) throws Exception {
        List<String> pubMedIds = xdbIdDao.getPubmedIdsWithoutReference(cutoffDate);
        Collections.shuffle(pubMedIds);
        return pubMedIds;
    }

    public List<String> getPubmedIdsForActiveReferences() throws Exception {
        return refDao.getPubmedIdsForActiveReferences();
    }

    public int insertAuthor(Author auth) throws Exception{
        return refDao.insertAuthor(auth);
    }

    public void updateAuthor(Author auth) throws Exception{
        int rowCount = refDao.updateAuthor(auth);
    }

    public int insertRefAuthorAssoc(int refKey, int authorKey, int authorOrder) throws Exception{
        int rowsInserted = assDao.insertRefAuthorAssociation(refKey, authorKey, authorOrder);
        return rowsInserted;
    }

    public int insertXdbId(XdbId xdbObj) throws Exception{
        return xdbIdDao.insertXdb(xdbObj);
    }

    public void fixDuplicateAuthors() throws Exception {
        String sql1 = "SELECT a1.author_key,a2.author_key,a1.author_lname,a2.author_fname,a1.author_iname,a1.author_suffix FROM authors a1,authors a2\n" +
                "WHERE a1.author_key < a2.author_key\n" +
                "  AND nvl(a1.author_lname,'?')=nvl(a2.author_lname,'?')\n" +
                "  AND nvl(a1.author_fname,'?')=nvl(a2.author_fname,'?')\n" +
                "  AND nvl(a1.author_iname,'?')=nvl(a2.author_iname,'?')\n" +
                "  AND nvl(a1.author_suffix,'?')=nvl(a2.author_suffix,'?')\n" +
                "ORDER BY dbms_random.random";
        String sql2 = "UPDATE rgd_ref_author SET author_key=? WHERE author_key=?";
        String sql3 = "DELETE FROM authors WHERE author_key=?";

        Logger log = LogManager.getLogger("deleted_authors");
        Connection conn = refDao.getConnection();
        PreparedStatement psDupAuthors = conn.prepareStatement(sql1);
        PreparedStatement psUpdateAssocs = conn.prepareStatement(sql2);
        PreparedStatement psDelAuthors = conn.prepareStatement(sql3);
        int dupAuthorsDeleted = 0;
        int author2RefAssocUpdated = 0;
        int multis = 0;
        ResultSet rs = psDupAuthors.executeQuery();
        Set<Integer> processedAuthorKeys = new HashSet<>();
        while(rs.next()) {
            int authorKeyOld = rs.getInt(1);
            int authorKeyNew = rs.getInt(2);
            String lastName = Utils.defaultString(rs.getString(3));
            String firstName = Utils.defaultString(rs.getString(4));
            String initials = Utils.defaultString(rs.getString(5));
            String suffix = Utils.defaultString(rs.getString(6));

            //
            if( !processedAuthorKeys.add(authorKeyOld) ||
                    !processedAuthorKeys.add(authorKeyNew) ) {
                multis++;
                continue;
            }

            log.info("NEW_AUTHOR_KEY="+authorKeyNew+" OLD_AUTHOR_KEY="+authorKeyOld+" LASTNAME="+lastName+
                " FIRSTNAME="+firstName+" INITIALS="+initials+" SUFFIX="+suffix);

            psUpdateAssocs.setInt(1, authorKeyNew);
            psUpdateAssocs.setInt(2, authorKeyOld);
            psUpdateAssocs.execute();
            author2RefAssocUpdated += psUpdateAssocs.getUpdateCount();

            psDelAuthors.setInt(1, authorKeyOld);
            psDelAuthors.execute();
            dupAuthorsDeleted += psDelAuthors.getUpdateCount();
        }
        conn.close();

        System.out.println("DUPLICATE AUTHORS DELETED: "+dupAuthorsDeleted);
        System.out.println("AUTHOR-TO-REF ASSOCS UPDATED:"+author2RefAssocUpdated);
        System.out.println("MULTIS:"+multis);
    }

    public int getLastReferenceWithPmcId() throws Exception {
        String sql = "SELECT MAX(r.rgd_id) FROM references r,rgd_acc_xdb x where r.rgd_id=x.rgd_id and xdb_key=146";
        return xdbIdDao.getCount(sql);
    }

    public List<Integer> getActiveReferenceRgdIds(int minRgdId) throws Exception {
        String sql = """
            SELECT x.rgd_id FROM references r,rgd_acc_xdb x,rgd_ids i
            WHERE r.rgd_id=x.rgd_id AND x.rgd_id=i.rgd_id AND object_status='ACTIVE' AND x.rgd_id>?
            ORDER BY x.rgd_id
            """;
        return IntListQuery.execute(xdbIdDao, sql, minRgdId);
    }

    public String getPubMedIdForRefRgdId(int refRgdId) throws Exception {
        List<XdbId> xdbIds = xdbIdDao.getPubmedIdsByRefRgdId(refRgdId);
        if( xdbIds.isEmpty() ) {
            return null;
        }
        return xdbIds.get(0).getAccId();
    }

    public int getReferenceRgdIdByPubmedId(String pmid) throws Exception {
        return refDao.getReferenceRgdIdByPubmedId(pmid);
    }

    /**
     * every reference that has a PubMed id, keyed by that PubMed id, whatever its object status;
     * OBJECT_KEY 12 is a reference
     */
    public Map<String, RetractedReferences.RefInRgd> getReferencesByPubmedId() throws Exception {

        String sql = """
            SELECT x.acc_id, i.rgd_id, i.object_status, i.created_date, i.last_modified_date, r.title
            FROM rgd_acc_xdb x, rgd_ids i, references r
            WHERE x.xdb_key = 2
              AND x.rgd_id = i.rgd_id
              AND i.rgd_id = r.rgd_id
              AND i.object_key = 12
            """;

        Map<String, RetractedReferences.RefInRgd> refs = new HashMap<>();
        Connection conn = refDao.getConnection();
        try {
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setFetchSize(5000);
            ResultSet rs = ps.executeQuery();
            while( rs.next() ) {
                RetractedReferences.RefInRgd ref = new RetractedReferences.RefInRgd();
                ref.rgdId = rs.getInt(2);
                ref.objectStatus = Utils.defaultString(rs.getString(3));
                ref.createdDate = rs.getDate(4);
                ref.lastModifiedDate = rs.getDate(5);
                ref.title = Utils.defaultString(rs.getString(6));
                refs.put(Utils.defaultString(rs.getString(1)).trim(), ref);
            }
            rs.close();
            ps.close();
        } finally {
            conn.close();
        }
        return refs;
    }

    /** replaces the contents of REFERENCES_RETRACTED with the retractions just downloaded */
    public int refreshRetractedReferences(Collection<RetractedReferences.Retraction> retractions) throws Exception {

        String insertSql = """
            INSERT INTO references_retracted
              (original_pmid, retraction_pmid, retraction_date, nature, reason,
               original_pmid_rgd_id, date_created_in_rgd, date_retracted_in_rgd)
            VALUES (?,?,?,?,?,?,?,?)
            """;

        int inserted = 0;
        Connection conn = refDao.getConnection();
        try {
            PreparedStatement psDelete = conn.prepareStatement("DELETE FROM references_retracted");
            psDelete.executeUpdate();
            psDelete.close();

            PreparedStatement ps = conn.prepareStatement(insertSql);
            int batch = 0;
            for( RetractedReferences.Retraction r: retractions ) {
                ps.setString(1, r.originalPmid);
                ps.setString(2, Utils.isStringEmpty(r.retractionPmid) ? null : r.retractionPmid);
                ps.setDate(3, r.retractionDate==null ? null : new java.sql.Date(r.retractionDate.getTime()));
                ps.setString(4, r.nature);
                ps.setString(5, r.reason);
                if( r.rgdId==null ) {
                    ps.setNull(6, Types.NUMERIC);
                } else {
                    ps.setInt(6, r.rgdId);
                }
                ps.setDate(7, r.dateCreatedInRgd==null ? null : new java.sql.Date(r.dateCreatedInRgd.getTime()));
                ps.setDate(8, r.dateRetractedInRgd==null ? null : new java.sql.Date(r.dateRetractedInRgd.getTime()));
                ps.addBatch();

                if( ++batch >= 1000 ) {
                    inserted += Arrays.stream(ps.executeBatch()).sum();
                    batch = 0;
                }
            }
            if( batch > 0 ) {
                inserted += Arrays.stream(ps.executeBatch()).sum();
            }
            ps.close();
        } finally {
            conn.close();
        }
        return inserted;
    }

    /**
     * FULL_ANNOT.ASPECT to the id of the ontology its terms come from, f.e. 'D' to 'RDO';
     * ontologies that are not tied to an aspect are skipped
     */
    public Map<String, String> getAspectOntologies() throws Exception {

        Map<String, String> aspectOntologies = new HashMap<>();
        for( Ontology ont: ontDao.getOntologies() ) {
            if( !Utils.isStringEmpty(ont.getAspect()) ) {
                aspectOntologies.put(ont.getAspect(), ont.getId());
            }
        }
        return aspectOntologies;
    }

    /** annotation counts per reference, broken down by aspect and by data source */
    public Map<Integer, RetractedReferences.AnnotStats> getAnnotationStats(Collection<Integer> refRgdIds) throws Exception {

        Map<Integer, RetractedReferences.AnnotStats> stats = new HashMap<>();
        if( refRgdIds.isEmpty() ) {
            return stats;
        }

        List<Integer> ids = new ArrayList<>(refRgdIds);
        Connection conn = refDao.getConnection();
        try {
            // an Oracle IN list is capped at 1000 entries
            for( int start=0; start<ids.size(); start+=1000 ) {
                List<Integer> chunk = ids.subList(start, Math.min(start+1000, ids.size()));

                StringBuilder sql = new StringBuilder(
                        "SELECT ref_rgd_id, aspect, data_src, COUNT(*) FROM full_annot WHERE ref_rgd_id IN (");
                for( int i=0; i<chunk.size(); i++ ) {
                    sql.append(i>0 ? ",?" : "?");
                }
                sql.append(") GROUP BY ref_rgd_id, aspect, data_src");

                PreparedStatement ps = conn.prepareStatement(sql.toString());
                for( int i=0; i<chunk.size(); i++ ) {
                    ps.setInt(i+1, chunk.get(i));
                }
                ResultSet rs = ps.executeQuery();
                while( rs.next() ) {
                    int refRgdId = rs.getInt(1);
                    String aspect = Utils.defaultString(rs.getString(2));
                    String dataSrc = Utils.defaultString(rs.getString(3));
                    int count = rs.getInt(4);

                    RetractedReferences.AnnotStats s =
                            stats.computeIfAbsent(refRgdId, k -> new RetractedReferences.AnnotStats());
                    s.total += count;
                    s.byAspect.merge(aspect, count, Integer::sum);
                    s.bySource.merge(dataSrc, count, Integer::sum);
                }
                rs.close();
                ps.close();
            }
        } finally {
            conn.close();
        }
        return stats;
    }
}
