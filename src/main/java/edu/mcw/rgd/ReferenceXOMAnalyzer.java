package edu.mcw.rgd;


import edu.mcw.rgd.datamodel.Author;
import edu.mcw.rgd.datamodel.Reference;
import edu.mcw.rgd.process.CounterPool;
import edu.mcw.rgd.xml.XomAnalyzer;
import nu.xom.Element;
import nu.xom.Elements;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Created by IntelliJ IDEA.
 * User: pjayaraman
 * Date: 2/27/12
 */
public class ReferenceXOMAnalyzer extends XomAnalyzer {

    // lof all updates of reference objects with pubmed ids
    private static final Logger logUpdates = LogManager.getLogger("updates");

    private Map<String, Integer> mapPubmedIdToRefRgdId;
    private CounterPool counters;

    ReferenceUpdateDAO dao = new ReferenceUpdateDAO();

    public ReferenceXOMAnalyzer(Map<String, Integer> mapPubmedIdToRefRgdId, CounterPool counters){

        this.mapPubmedIdToRefRgdId = mapPubmedIdToRefRgdId;
        this.counters = counters;
    }

    public void initRecord(String name){
        // just start a new record
        //System.out.println("Start processing pubMed record:" + name);
    }

    public Element parseRecord(Element node) {

        String issn="";
        String jourVol = "";
        String jourIssue = "";
        java.util.Date pubDate = new java.util.Date();
        String title = "";
        String abbreviatedTitle = "";
        String medlinePg = "";
        String abstractText = "";
        String pubmedID = "";
        String doiValue = "";
        String refType="";
        Reference refObj;
        Reference modifiedReference  = new Reference();
        ReferenceUpdateObject refUpdateObject = new ReferenceUpdateObject(dao);

        if(node.getLocalName().equals("PubmedArticle")){

            Elements pubmedInfoChildEle = node.getChildElements();
            for(int firstNode=0; firstNode<pubmedInfoChildEle.size(); firstNode++){
                Element pubmedChildEle = pubmedInfoChildEle.get(firstNode);

                if(pubmedChildEle.getLocalName().equals("MedlineCitation")){

                    Elements medlineElements = pubmedChildEle.getChildElements();
                    for(int medline=0; medline<medlineElements.size(); medline++){
                        Element medlineEle = medlineElements.get(medline);

                        if(medlineEle.getLocalName().equals("PMID")){
                            pubmedID = medlineEle.getValue();
                            //logUpdates.debug("here is the pubmed ID:\t" + pubmedID);

                            //increment count of downloaded pubmed ids
                            counters.increment("pubmedIdsDownloaded");
                        }
                        else if(medlineEle.getLocalName().equals("Article")){
                            //String publicationType = medlineEle.getAttributeValue("PubModel").toString();
                            //System.out.println("here is the attribute value for publication type:\t" + publicationType);

                            Elements articleEle = medlineEle.getChildElements();
                            for(int art=0; art<articleEle.size();art++){
                                Element artSubElements = articleEle.get(art);
                                if(artSubElements.getLocalName().equals("Journal")){
                                    Elements journalChildren = artSubElements.getChildElements();
                                    for(int j=0;j<journalChildren.size();j++){
                                        Element jc = journalChildren.get(j);
                                        if(jc.getLocalName().equals("ISSN")){
                                            issn = jc.getValue();
                                        }
                                        else if(jc.getLocalName().equals("JournalIssue")){
                                            Elements journalEle = jc.getChildElements();
                                            for(int je=0; je<journalEle.size(); je++){
                                                Element journalIssChildren = journalEle.get(je);
                                                if(journalIssChildren.getLocalName().equals("Volume")){
                                                    jourVol = journalIssChildren.getValue();
                                                }
                                                else if(journalIssChildren.getLocalName().equals("Issue")){
                                                    jourIssue = journalIssChildren.getValue();
                                                }
                                                else if(journalIssChildren.getLocalName().equals("PubDate")){
                                                    pubDate = parsePubDate(journalIssChildren);
                                                }
                                            }
                                        }

                                        else if(jc.getLocalName().equals("Title")){
                                            title = jc.getValue();
                                        }
                                        else if(jc.getLocalName().equals("ISOAbbreviation")){
                                            abbreviatedTitle = jc.getValue();
                                        }
                                    }
                                }
                                else if(artSubElements.getLocalName().equals("ArticleTitle")){
                                    //referenceTitle = artSubElements.getValue();
                                }
                                else if(artSubElements.getLocalName().equals("Pagination")){
                                    Elements pages = artSubElements.getChildElements();
                                    for(int pg=0; pg<pages.size(); pg++){
                                        Element pgMedline = pages.get(pg);
                                        if(pgMedline.getLocalName().equals("MedlinePgn")){
                                            medlinePg = pgMedline.getValue();
                                        }
                                    }
                                }
                                else if(artSubElements.getLocalName().equals("Abstract")){
                                    Elements absEle = artSubElements.getChildElements();
                                    for(int abs=0;abs<absEle.size();abs++){
                                        Element absText = absEle.get(abs);
                                        if(absText.getLocalName().equals("AbstractText")){
                                            abstractText = absText.getValue();
                                        }
                                    }
                                }
                                else if(artSubElements.getLocalName().equals("AuthorList")){
                                    List<Author> authorList = parseAuthorList(artSubElements);
                                    if( !authorList.isEmpty() ) {
                                        refUpdateObject.setAuthorsList(authorList);
                                    }
                                }else if(artSubElements.getLocalName().equals("PublicationTypeList")){
                                    Elements pubTypeLists = artSubElements.getChildElements();

                                    for(int pubType=0; pubType<pubTypeLists.size(); pubType++){
                                        Element refTypeElement = pubTypeLists.get(pubType);

                                        if((refTypeElement.getLocalName().equals("PublicationType")) && (refTypeElement.getValue().equalsIgnoreCase("Journal Article"))){
                                            refType = "JOURNAL ARTICLE";
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                else if(pubmedChildEle.getLocalName().equals("PubmedData")){
                    doiValue = parseDoi(pubmedChildEle);
                }
            }
        }

        if(pubmedID.length()>0){
            refObj = retrieveReferenceObjforPubmedId(pubmedID);

            if(refObj!=null){
                logUpdates.debug("updating Reference Object:" + refObj.getRgdId() +" with pubmedId:"+pubmedID);

                refUpdateObject.setReferenceObj(refObj);
                refUpdateObject.setPubmedID(pubmedID);
                refUpdateObject.setIssn(issn);
                modifiedReference.setRefAbstract(abstractText);
                modifiedReference.setDoi(doiValue);
                modifiedReference.setIssue(jourIssue);
                modifiedReference.setVolume(jourVol);
                modifiedReference.setPages(medlinePg);
                modifiedReference.setPubDate(pubDate);
                modifiedReference.setPublication(abbreviatedTitle);
                modifiedReference.setRgdId(refObj.getRgdId());
                modifiedReference.setReferenceType(refType);
                modifiedReference.setSpeciesTypeKey(refObj.getSpeciesTypeKey());
                modifiedReference.setTitle(title);
                refUpdateObject.setUpdatedRefObj(modifiedReference);
                refUpdateObject = refUpdateObject.createCitation(refUpdateObject);

                refUpdateObject.updateReferenceObject(refUpdateObject);
            }

        }else{
            System.out.println("no pubmedid Found!!");
        }


        //System.out.println("number of Downloaded PubmedIds: " + downloadedPubmed);
        //System.out.println("number of dois: " + doiNumberCount);
        return null;
    }

    private Date parsePubDate(Element journalIssChildren) {
        Elements pubDateChildren = journalIssChildren.getChildElements();
        String pubYr=null, pubMnth=null, pubDay=null;
        for(int pub=0; pub<pubDateChildren.size();pub++){
            Element pubDtCh = pubDateChildren.get(pub);
            if(pubDtCh.getLocalName().equals("Year")){
                pubYr = pubDtCh.getValue();
            }
            else if(pubDtCh.getLocalName().equals("Month")){
                pubMnth = pubDtCh.getValue();
            }
            else if(pubDtCh.getLocalName().equals("Day")){
                pubDay = pubDtCh.getValue();
            }
        }

        return makeDateFromString(pubYr, pubMnth, pubDay);
    }

    List<Author> parseAuthorList(Element artSubElements) {

        Elements authorListElements = artSubElements.getChildElements();
        List<Author> authorsList = new ArrayList<Author>();

        for(int aut=0; aut<authorListElements.size(); aut++){
            Element auth = authorListElements.get(aut);

            if(auth.getLocalName().equals("Author")){
                if(auth.getAttribute(0).getValue().equals("Y")){

                    Author authObj = new Author();

                    Elements names = auth.getChildElements();
                    for(int n=0;n<names.size();n++){
                        Element authName = names.get(n);

                        if(authName.getLocalName().equals("LastName")){
                            authObj.setLastName(authName.getValue());
                        }
                        else if(authName.getLocalName().equals("ForeName")){
                            authObj.setFirstName(authName.getValue());
                        }
                        else if(authName.getLocalName().equals("Initials")){
                            authObj.setInitials(authName.getValue());
                        }else if(authName.getLocalName().equals("Suffix")){
                            authObj.setSuffix(authName.getValue());
                        }
                    }

                    authorsList.add(authObj);
                }
            }
        }
        return authorsList;
    }

    private String parseDoi(Element pubmedChildEle) {

        String doiValue = "";
        Elements pmData = pubmedChildEle.getChildElements();
        for(int pm=0;pm<pmData.size();pm++){
            Element pubmedInformation = pmData.get(pm);

            if(pubmedInformation.getLocalName().equals("ArticleIdList")){
                Elements articles = pubmedInformation.getChildElements();

                for(int art=0;art<articles.size();art++){
                    Element artPubmed = articles.get(art);
                    if(artPubmed.getLocalName().equals("ArticleId")){
                        if(artPubmed.getAttributeValue("IdType").equals("doi")){
                            doiValue = artPubmed.getValue();

                            counters.increment("doiCount");
                        }
                    }
                }
            }
        }
        return doiValue;
    }

    private Date makeDateFromString(String year, String month, String day) {

        if( year==null || year.isEmpty() )
            return null;
        if( month==null || month.isEmpty() )
            month = "Jan";
        if( day==null || day.isEmpty() )
            day = "01";

        Date dt;
        try {
            dt = _dateFormat.parse(year+month+day);
        }
        catch(ParseException e) {
            dt = null;
        }
        return dt;
    }
    static DateFormat _dateFormat = new SimpleDateFormat("yyyyMMMdd");



    Reference retrieveReferenceObjforPubmedId(String pubmedID){

        Integer refRgdId = mapPubmedIdToRefRgdId.get(pubmedID);
        if( refRgdId==null )
            return new Reference();

        Reference ref;
        try{
            ref = dao.getReference(refRgdId);
        }catch( Exception e){
            ref = null;
        }

        return ref;
    }
}
