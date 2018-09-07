package edu.mcw.rgd;

import edu.mcw.rgd.datamodel.Author;
import edu.mcw.rgd.datamodel.Reference;
import edu.mcw.rgd.datamodel.XdbId;
import edu.mcw.rgd.process.Utils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;


/**
 * Created by IntelliJ IDEA.
 * User: pjayaraman
 * Date: 11/12/12
 * Time: 2:00 PM
 */
public class ReferenceUpdateObject{

    private ReferenceUpdateDAO dao;
    List<Author> authorsList;
    Reference referenceObj;
    Reference updatedRefObj;
    String issn="";
    String pubmedID="";
    private static final Log logStatus = LogFactory.getLog("log_status");
    private static final Log logConflicts = LogFactory.getLog("log_conflicts");

    public ReferenceUpdateObject(ReferenceUpdateDAO dao) {
        this.dao = dao;
    }

    public void updateReferenceObject(ReferenceUpdateObject refUpdObj) {

        try {
            Reference objectTobeUpdated = compareRefObjs(refUpdObj);
            dao.updateReferenceField(objectTobeUpdated);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public int getPubmedAssocInDB(Reference objectTobeUpdated, String termSearchUrl, String attR) throws Exception {

        String articleString = getSynonymAPI(termSearchUrl, attR, objectTobeUpdated);
        String possiblePubMedId = matchArticleRetrievePubmedId(articleString, objectTobeUpdated);

        if(possiblePubMedId!=null){
            XdbId newXdbObj = new XdbId();
            newXdbObj.setAccId(possiblePubMedId);
            newXdbObj.setCreationDate(new Date());
            newXdbObj.setLinkText(objectTobeUpdated.getCitation());
            newXdbObj.setModificationDate(new Date());
            newXdbObj.setRgdId(objectTobeUpdated.getRgdId());
            newXdbObj.setSrcPipeline("ReferenceUpdate");
            newXdbObj.setXdbKey(XdbId.XDB_KEY_PUBMED);

            System.out.println("inserting object into DB:" + newXdbObj.getRgdId()+"||"+newXdbObj.getXdbKey()+"||"+newXdbObj.getAccId()+"||"
            +newXdbObj.getCreationDate()+"||"+newXdbObj.getNotes()+"||"+newXdbObj.getLinkText()+"||"+newXdbObj.getSrcPipeline()+"||"
            +newXdbObj.getModificationDate());

            logStatus.info("inserting object into DB:" + newXdbObj.getRgdId()+"||"+newXdbObj.getXdbKey()+"||"+newXdbObj.getAccId()+"||"
            +newXdbObj.getCreationDate()+"||"+newXdbObj.getNotes()+"||"+newXdbObj.getLinkText()+"||"+newXdbObj.getSrcPipeline()+"||"
            +newXdbObj.getModificationDate());

            return dao.insertPmidRefRgdAssoc(newXdbObj);
        }else{
            return 0;
        }
    }


    private String getSynonymAPI(String termSearchUrl, String attR, Reference objectTobeUpdated) throws Exception {

        //String term2findSyn = term2findSyn4.replaceAll("[^a-zA-Z]+"," ");
        String term2findSyn = "";
        /*if(objectTobeUpdated.getPublication()!=null && objectTobeUpdated.getPublication().length()>0){
            term2findSyn+="("+objectTobeUpdated.getPublication().replaceAll("-", " ")+")";
        }*/

        List<String> symbolsToEscapeList = getSymbolsToEscapeList();
        if(objectTobeUpdated.getTitle()!=null && objectTobeUpdated.getTitle().length()>0){

            String title = objectTobeUpdated.getTitle();

            //String title_enc = URLEncoder.encode(title, "UTF-8");
            String title_enc = title;
            for(String sym : symbolsToEscapeList){
                if(title.contains(sym)){
                    title_enc = title.replaceAll("\\"+sym, "");
                    title = title_enc;
                    //System.out.println("TITLE:"+title);
                }
            }
            term2findSyn+=" title:("+title_enc+")";
        }

        /*if(objectTobeUpdated.getRefAbstract()!=null && objectTobeUpdated.getRefAbstract().length()>0){
            String abs = objectTobeUpdated.getRefAbstract().replaceAll("-"," ");
            String absEnc = URLEncoder.encode(abs, "UTF-8");
            term2findSyn+=" abstract:("+ absEnc +")";
        }*/

        List<Author> authorsList = dao.getAuthorsListFromDB(objectTobeUpdated.getKey());
        if(authorsList!=null){
            term2findSyn+= " authors:("+authorsList.get(0).getLastName()+" "+authorsList.get(0).getInitials()+")";
            /*for(Author a : authorsList){
                term2findSyn+=a.getLastName()+" "+a.getInitials()+" ";
            }*/
        }

        //String url = "http://hastings/OntoSolr/select?q="+ URLEncoder.encode(term2findSyn, "UTF-8")+"%20"+id2findSyn4+"&fl=score&fq=cat:"+ontId;
        String urlTerm = urlAPIBuilder(termSearchUrl, attR, term2findSyn);

        try {
            //URI uri = new URI(urlTerm);
            URL apiEndPoint = new URL(urlTerm);
            URLConnection connection = apiEndPoint.openConnection();
            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder builder = new StringBuilder();
            String inputLine;
            while ((inputLine = in.readLine()) != null){
                builder.append(inputLine);
                builder.append("\n");
            }
            in.close();
            return builder.toString();

        }catch (MalformedURLException e) {
            throw new RuntimeException("Url of the API is not correct: " + e);
        }catch (IOException e) {
            throw new RuntimeException("Cannot communicate with the server: "+e);
        }

    }

    public static List<String> getSymbolsToEscapeList() {

        List<String> symbolsToEscapeList = new ArrayList<String>();
        symbolsToEscapeList.add("+");
        symbolsToEscapeList.add("-");
        symbolsToEscapeList.add("&&");
        symbolsToEscapeList.add("||");
        symbolsToEscapeList.add("!");
        symbolsToEscapeList.add("(");
        symbolsToEscapeList.add(")");
        //symbolsToEscapeList.add("{");
        //symbolsToEscapeList.add("}");
        symbolsToEscapeList.add("[");
        symbolsToEscapeList.add("]");
        symbolsToEscapeList.add("^");
        //symbolsToEscapeList.add("\"");
        symbolsToEscapeList.add("~");
        symbolsToEscapeList.add("*");
        symbolsToEscapeList.add("?");
        symbolsToEscapeList.add(":");
        //symbolsToEscapeList.add("\\");

        return symbolsToEscapeList;
    }


    public String matchArticleRetrievePubmedId(String articleString, Reference referenceObject) throws Exception{

        String pubmedIdsInArticle = "";

        //System.out.println("comparing results with this reference object:" + referenceObject.getTitle()+ "_RGDID:" + referenceObject.getRgdId());

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        org.w3c.dom.Document doc = builder.parse(new InputSource(new StringReader(articleString)));
        NodeList nodesList = doc.getFirstChild().getFirstChild().getNextSibling().getNextSibling().getChildNodes();
        int match = 0;
        for(int n=0; n<nodesList.getLength(); n++){

            NodeList eleList = nodesList.item(n).getChildNodes();
            String authors=""; String pmidShort=""; String title="";
            for(int i=0; i<eleList.getLength(); i++){
                if(eleList.item(i).getNodeName().equals("str")){
                    //System.out.println("elementsSize is: " + eleList.item(i).getNodeName());
                    Node strElement = eleList.item(i).getAttributes().item(0);

                    //if(strElement.getNodeValue().equalsIgnoreCase("abstract")){
                        //abstr = eleList.item(i).getTextContent();
                    //}

                    if(strElement.getNodeValue().equalsIgnoreCase("authors")){
                        authors = eleList.item(i).getTextContent();
                        //System.out.println("here is the id: " + termAcc);
                    }

                }
                if(eleList.item(i).getNodeName().equalsIgnoreCase("long")){
                    //System.out.println("elementsSize is: " + eleList.item(i).getNodeName());
                    Node strElement = eleList.item(i).getAttributes().item(0);

                    if(strElement.getNodeValue().equals("pmid_l")){
                        pmidShort = eleList.item(i).getTextContent();
                        //System.out.println("here is the shortened id without 0s: " + shortTerm);
                    }
                }
                if(eleList.item(i).getNodeName().equalsIgnoreCase("arr")){
                    //System.out.println("elementsSize is: " + eleList.item(i).getNodeName());
                    Node strElement = eleList.item(i).getAttributes().item(0);

                    if(strElement.getNodeValue().equals("title")){
                        title = eleList.item(i).getTextContent();
                        //System.out.println("here is the shortened id without 0s: " + shortTerm);
                    }
                }
            }

            //System.out.println("all values done.. may proceed to next node.."+ pmidShort + "_" + title);

            match = compareArticleWithObject(authors, pmidShort, title, referenceObject);
            if(match==1){

                System.out.println("its a match:"+pmidShort+ " having rgdId: " + referenceObject.getRgdId()+" with title:"+title);
                logStatus.info("Match:"+pmidShort+ " having rgdId: " + referenceObject.getRgdId()+" with title:"+title);

                pubmedIdsInArticle = pmidShort;
                break;
            }
        }

        //System.out.println("should be comparing here..");
        if(match==1){
            return pubmedIdsInArticle;
        }else{
            return null;
        }

    }

    private int compareArticleWithObject(String authors, String pmidShort, String title, Reference referenceObj) throws Exception {
        List<Author> authorsList = dao.getAuthorsListFromDB(referenceObj.getKey());
        String[] authorsString = authors.split(", ");
        int authorMatch=0;

        if(authorsList!=null && authorsList.size()>0){
            if( Utils.stringsAreEqualIgnoreCase(referenceObj.getTitle(), title) && (authorsString.length!=authorsList.size())){

                String msg = "CONFLICT: AuthorSize mismatch..check authors PMID:" + pmidShort +", RGDID:" + referenceObj.getRgdId();
                System.out.println(msg);
                logConflicts.info(msg);

            }else if( Utils.stringsAreEqualIgnoreCase(referenceObj.getTitle(), title) && (authorsString.length==authorsList.size())){
                for(int a=0; a<authorsList.size(); a++){
                    String authName = authorsList.get(a).getLastName()+" "+authorsList.get(a).getInitials();
                    String authorString = authorsString[a];
                    if(authorsString[a].endsWith(".")){
                        authorString = authorsString[a].substring(0, (authorsString[a].length()-1));
                    }

                    if(authName.equalsIgnoreCase(authorString)){
                        authorMatch=1;
                    }
                }
            }
        }

        if(referenceObj.getTitle().compareToIgnoreCase(title)==0){
            if(authorMatch==1){
                return 1;
            }else{
                return 0;
            }
        }else{
            return 0;
        }
    }

    public String urlAPIBuilder(String hostAddress, String attRows, String term2findSyn) throws Exception{
        String api="";
        int flag=0;

        if(hostAddress.length()>0){
            api+=hostAddress;
        }else{
            flag=1;
        }
        if(term2findSyn.length()>0){
            api+= URLEncoder.encode(term2findSyn, "UTF-8");
        }else{
            flag=1;
        }

        if(attRows.length()>0){
            api+="&"+attRows;
        }else{
            api+="&rows=10";
        }


        if(flag==0)
            return api;
        else
            throw new Exception("argument parameters arent complete");
    }

    public ReferenceUpdateObject createCitation(ReferenceUpdateObject refUpdObj) {
        String cit="";
        List<Author> authorsList = refUpdObj.getAuthorsList();
        if(authorsList==null){
            cit = "NO_AUTHOR,";
        }else{
            if(authorsList.size()>2){
                cit = authorsList.get(0).getFirstName() + " " + authorsList.get(0).getLastName() + ", et al.,";
            }else if(authorsList.size()==2){
                cit = authorsList.get(0).getFirstName() + " " + authorsList.get(0).getLastName() + authorsList.get(1).getFirstName()
                        + " " + authorsList.get(1).getLastName() + ",";
            }else if(authorsList.size()==1){
                cit = authorsList.get(0).getFirstName() + " " + authorsList.get(0).getLastName() + ",";
            }
        }
        if(refUpdObj.getUpdatedRefObj().getPublication()!=null){
            cit += refUpdObj.getUpdatedRefObj().getPublication();
        }

        if(refUpdObj.getUpdatedRefObj().getPubDate()!=null){
            cit += " " + refUpdObj.getUpdatedRefObj().getPubDate() + ";";
        }else{
            cit += " ;";
        }

        if(refUpdObj.getUpdatedRefObj().getVolume()!=null && refUpdObj.getUpdatedRefObj().getIssue()!=null){
            cit += refUpdObj.getUpdatedRefObj().getVolume()+"(" + refUpdObj.getUpdatedRefObj().getIssue() + "):";
        }else{
            cit += ":";
        }

        if(refUpdObj.getUpdatedRefObj().getPages()!=null){
            cit += refUpdObj.getUpdatedRefObj().getPages() + ".";
        }else{
            cit += ".";
        }

        refUpdObj.getUpdatedRefObj().setCitation(cit);

        return refUpdObj;
    }



    public Reference compareRefObjs(ReferenceUpdateObject refUpdateObject) throws Exception {

        Reference updatedreferenceObject = refUpdateObject.getUpdatedRefObj(); // incoming reference object
        Reference refObjToBeUpdated = refUpdateObject.getReferenceObj();


        //if title rgdid and abstract i sthe same check if volume issue publication and pages are the same. else you know that those fields may need updating..
        if(refObjToBeUpdated.getRgdId()==updatedreferenceObject.getRgdId()){

            // && (originalRefObject.getRefAbstract().equalsIgnoreCase(updatedreferenceObject.getRefAbstract()))
            if((refObjToBeUpdated.getRefAbstract()==null) && (updatedreferenceObject.getRefAbstract()!=null)){
                refObjToBeUpdated.setRefAbstract(updatedreferenceObject.getRefAbstract());
            }

            //&& (originalRefObject.getTitle().equalsIgnoreCase(updatedreferenceObject.getTitle()))
            if((refObjToBeUpdated.getTitle()==null) && (updatedreferenceObject.getTitle()!=null)){
                refObjToBeUpdated.setTitle(updatedreferenceObject.getTitle());
            }


            if((refObjToBeUpdated.getVolume()==null) && (updatedreferenceObject.getVolume()!=null)){
                refObjToBeUpdated.setVolume(updatedreferenceObject.getVolume());
            }

            if((updatedreferenceObject.getIssue()!=null) && (refObjToBeUpdated.getIssue()==null)){
                refObjToBeUpdated.setIssue(updatedreferenceObject.getIssue());
            }

            if((updatedreferenceObject.getPages()!=null) && (refObjToBeUpdated.getPages()==null)){
                refObjToBeUpdated.setPages(updatedreferenceObject.getPages());
            }

            if((updatedreferenceObject.getCitation()!=null) && (refObjToBeUpdated.getCitation()==null)){
                refObjToBeUpdated.setCitation(updatedreferenceObject.getCitation());
            }

            if((updatedreferenceObject.getDoi()!=null) && (refObjToBeUpdated.getDoi()==null)){
                refObjToBeUpdated.setDoi(updatedreferenceObject.getDoi());
            }

            //update authorsList:
            ReferenceUpdateDAO refUpdateDao = new ReferenceUpdateDAO();
            int referenceKey = refObjToBeUpdated.getKey();
            List<Author> originalAuthorsList = refUpdateDao.getAuthorsListFromDB(referenceKey);

            int authorOrder=1;
            if(originalAuthorsList!=null && authorsList!=null){
                if(originalAuthorsList.size()<authorsList.size()){

                    for (Author author: authorsList) {
                        int authorFound = 0;

                        for (Author originalAuthor : originalAuthorsList) {
                            if (Utils.stringsAreEqual(author.getInitials(), originalAuthor.getInitials()) &&
                                    Utils.stringsAreEqual(author.getLastName(), originalAuthor.getLastName())) {
                                //update authorObject
                                refUpdateDao.updateAuthor(originalAuthor);
                                authorFound = 1;
                                author.setKey(originalAuthor.getKey());
                                break;
                            }

                        }

                        if (authorFound == 0) {
                            //insert new author
                            refUpdateDao.insertAuthor(author);
                        }

                    }

                    //check if ref-authorkey association has been made..
                    authorOrder = originalAuthorsList.size();
                    for(int order=authorOrder; order<authorsList.size(); order++){
                        refUpdateDao.insertRefAuthorAssoc(referenceKey, authorsList.get(order).getKey(), (order+1));
                    }

                }
            }

        }

        return refObjToBeUpdated;

    }



    public Reference getUpdatedRefObj() {
        return updatedRefObj;
    }

    public void setUpdatedRefObj(Reference updatedRefObj) {
        this.updatedRefObj = updatedRefObj;
    }

    public Reference getReferenceObj() {
        return referenceObj;
    }

    public void setReferenceObj(Reference referenceObj) {
        this.referenceObj = referenceObj;
    }

    public String getIssn() {
        return issn;
    }

    public void setIssn(String issn) {
        this.issn = issn;
    }

    public String getPubmedID() {
        return pubmedID;
    }

    public void setPubmedID(String pubmedID) {
        this.pubmedID = pubmedID;
    }

    public List<Author> getAuthorsList() {
        return authorsList;
    }

    public void setAuthorsList(List<Author> authorsList) {
        this.authorsList = authorsList;
    }
}
