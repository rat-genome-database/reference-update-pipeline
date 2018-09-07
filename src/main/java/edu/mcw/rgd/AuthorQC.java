package edu.mcw.rgd;

import edu.mcw.rgd.dao.impl.*;
import edu.mcw.rgd.datamodel.*;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.List;

/**
 * Created by mtutaj on 12/19/2016.
 */
public class AuthorQC {

    GeneDAO gdao = new GeneDAO();
    TranscriptDAO tdao = new TranscriptDAO();

    public static void main(String[] args) throws Exception {
        new AuthorQC().run();
    }

    void run() throws Exception {
        String fileNameBase = "/tmp/d17d3TRANS57";
        BufferedReader reader = new BufferedReader(new FileReader(fileNameBase+"_IN.txt"));
        String headerIn = reader.readLine();
        String headerOut = "Human GeneId\tHuman Gene Symbol\t"+headerIn;
        BufferedWriter writer = new BufferedWriter(new FileWriter(fileNameBase+"_OUT.txt"));
        writer.write(headerOut+"\n");

        boolean parseTrFromCol1 = true;

        XdbIdDAO xdao = new XdbIdDAO();
        OrthologDAO odao = new OrthologDAO();

        int linesSkipped = 0;
        int linesWritten = 0;
        String line;
        List<Gene> genesChin;
        Gene geneChin, geneHuman;
        while( (line=reader.readLine())!=null ) {
            String[] cols = line.split("[\\t]", -1);

            if( parseTrFromCol1 ) {
                geneChin = getChinGene(cols[0]);
                if( geneChin==null ) {
                    linesSkipped++;
                    continue;
                }
            }
            else {
                String geneId = cols[cols.length - 2];
                genesChin = xdao.getGenesByXdbId(XdbId.XDB_KEY_ENTREZGENE, geneId);
                if (genesChin.isEmpty()) {
                    linesSkipped++;
                    continue;
                }
                geneChin = genesChin.get(0);
            }

            geneHuman = null;
            List<Ortholog> orthos = odao.getOrthologsForSourceRgdId(geneChin.getRgdId());
            for( Ortholog o: orthos ) {
                if( o.getDestSpeciesTypeKey()== SpeciesType.HUMAN ) {
                    geneHuman = gdao.getGene(o.getDestRgdId());
                    break;
                }
            }

            if( geneHuman!=null ) {
                List<XdbId> xdbIdsHuman = xdao.getXdbIdsByRgdId(XdbId.XDB_KEY_ENTREZGENE, geneHuman.getRgdId());
                writer.write(xdbIdsHuman.get(0).getAccId()+"\t"+geneHuman.getSymbol()+"\t"+line+"\n");
                linesWritten++;
            } else {
                linesSkipped++;
            }
        }
        writer.close();
        reader.close();

        System.out.println("LINES WRITTEN: "+linesWritten);
        System.out.println("LINES SKIPPED: "+linesSkipped);
    }

    Gene getChinGene(String col) throws Exception {
        // col: gi|918665165|ref|XM_013510246.1|
        int lastBarPos = col.lastIndexOf('|');
        int prevBarPos = col.lastIndexOf('|', lastBarPos-1);
        String accId = col.substring(prevBarPos+1, lastBarPos);
        int dotPos = accId.indexOf('.');
        String trAccId = accId.substring(0, dotPos);

        for( Transcript t: tdao.getTranscriptsByAccId(trAccId) ) {
            return gdao.getGene(t.getGeneRgdId());
        }
        return null;
    }
}
