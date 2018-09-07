package edu.mcw.rgd;

/**
 * Created by IntelliJ IDEA.
 * User: pjayaraman
 * Date: 3/8/12
 * Time: 4:49 PM
 */
public class ReferenceUpdateGlobalCounters {
    private static ReferenceUpdateGlobalCounters ourInstance = new ReferenceUpdateGlobalCounters();

    public static ReferenceUpdateGlobalCounters getInstance() {
        return ourInstance;
    }

    private ReferenceUpdateGlobalCounters() {
    }


    // GLOBAL COUNTERS
    private int refCount=0;
    private int doiCount=0;
    private int pubmedCount=0;
    private int pubmedIdsDownloadedCount=0;

    public void incrementRefCount() {
        refCount++;
    }

    public void incrementPubmedCount(){
        pubmedCount++;
    }

    public void incrementDoiCount(){
        doiCount++;
    }

    public void incrementDownloadedPubmedIdsCount(){
        pubmedIdsDownloadedCount++;
    }

    public int getRefCount() {
        return refCount;
    }

    public int getDoiCount() {
        return doiCount;
    }

    public int getPubmedCount() {
        return pubmedCount;
    }

    public int getPubmedIdsDownloadedCount() {
        return pubmedIdsDownloadedCount;
    }
}
