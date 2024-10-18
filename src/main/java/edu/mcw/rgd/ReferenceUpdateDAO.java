package edu.mcw.rgd;

import edu.mcw.rgd.dao.impl.AssociationDAO;
import edu.mcw.rgd.dao.impl.ReferenceDAO;
import edu.mcw.rgd.dao.impl.XdbIdDAO;
import edu.mcw.rgd.dao.spring.IntListQuery;
import edu.mcw.rgd.datamodel.Author;
import edu.mcw.rgd.datamodel.Reference;
import edu.mcw.rgd.datamodel.XdbId;
import edu.mcw.rgd.process.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
        String sql = "SELECT x.rgd_id FROM references r,rgd_acc_xdb x,rgd_ids i WHERE r.rgd_id=x.rgd_id AND x.rgd_id=i.rgd_id AND object_status='ACTIVE' AND x.rgd_id>? ORDER BY x.rgd_id";
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

}
