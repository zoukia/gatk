/*
 * Copyright (c) 2010 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.broadinstitute.sting.gatk.walkers;

import net.sf.picard.reference.ReferenceSequence;
import net.sf.picard.reference.ReferenceSequenceFile;
import net.sf.picard.reference.ReferenceSequenceFileFactory;
import net.sf.samtools.util.StringUtil;
import org.broadinstitute.sting.commandline.Advanced;
import org.broadinstitute.sting.commandline.Argument;
import org.broadinstitute.sting.commandline.Hidden;
import org.broadinstitute.sting.commandline.Output;
import org.broadinstitute.sting.gatk.contexts.ReferenceContext;
import org.broadinstitute.sting.gatk.io.StingSAMFileWriter;
import org.broadinstitute.sting.gatk.refdata.ReadMetaDataTracker;
import org.broadinstitute.sting.utils.BaseUtils;
import org.broadinstitute.sting.utils.Utils;
import org.broadinstitute.sting.utils.clipping.ClippingOp;
import org.broadinstitute.sting.utils.clipping.ClippingRepresentation;
import org.broadinstitute.sting.utils.clipping.ReadClipper;
import org.broadinstitute.sting.utils.collections.Pair;
import org.broadinstitute.sting.utils.sam.GATKSAMRecord;
import org.broadinstitute.sting.utils.sam.ReadUtils;

import java.io.File;
import java.io.PrintStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This tool provides simple, powerful read clipping capabilities to remove low quality strings of bases, sections of reads, and reads containing user-provided sequences.
 *
 *
 * <p>
 * It allows the user to clip bases in reads with poor quality scores, that match particular
 * sequences, or that were generated by particular machine cycles.
 *
 * <dl>
 *     <dt>Quality score based clipping</dt>
 *     <dd>
 *         Clip bases from the read in clipper from
 *         <br>argmax_x{ \sum{i = x + 1}^l (qTrimmingThreshold - qual)</br>
 *         to the end of the read.  This is blatantly stolen from BWA.
 *
 *         Walk through the read from the end (in machine cycle order) to the beginning, calculating the
 *         running sum of qTrimmingThreshold - qual.  While we do this, we track the maximum value of this
 *         sum where the delta > 0.  After the loop, clipPoint is either -1 (don't do anything) or the
 *         clipping index in the read (from the end).
 *     </dd>
 *     <dt>Cycle based clipping</dt>
 *     <dd>Clips machine cycles from the read. Accepts a string of ranges of the form start1-end1,start2-end2, etc.
 *     For each start/end pair, removes bases in machine cycles from start to end, inclusive. These are 1-based values (positions).
 *     For example, 1-5,10-12 clips the first 5 bases, and then three bases at cycles 10, 11, and 12.
 *     </dd>
 *     <dt>Sequence matching</dt>
 *     <dd>Clips bases from that exactly match one of a number of base sequences. This employs an exact match algorithm,
 *     filtering only bases whose sequence exactly matches SEQ.</dd>
 * </dl>
 *
 * </p>
 *
 * <h2>Input</h2>
 * <p>
 *     Any number of BAM files.
 * </p>
 *
 * <h2>Output</h2>
 * <p>
 *     A new BAM file containing all of the reads from the input BAMs with the user-specified clipping
 *     operation applied to each read.
 * </p>
 * <p>
 *     <h3>Summary output</h3>
 *     <pre>
 *     Number of examined reads              13
 *     Number of clipped reads               13
 *     Percent of clipped reads              100.00
 *     Number of examined bases              988
 *     Number of clipped bases               126
 *     Percent of clipped bases              12.75
 *     Number of quality-score clipped bases 126
 *     Number of range clipped bases         0
 *     Number of sequence clipped bases      0
 *     </pre>
 * </p>
 *
 * <p>
 *     <h3>Example clipping</h3>
 *     Suppose we are given this read:
 *     <pre>
 *     314KGAAXX090507:1:19:1420:1123#0        16      chrM    3116    29      76M     *       *       *
 *          TAGGACCCGGGCCCCCCTCCCCAATCCTCCAACGCATATAGCGGCCGCGCCTTCCCCCGTAAATGATATCATCTCA
 *          #################4?6/?2135;;;'1/=/<'B9;12;68?A79@,@==@9?=AAA3;A@B;A?B54;?ABA
 *     </pre>
 *
 *     If we are clipping reads with -QT 10 and -CR WRITE_NS, we get:
 *
 *     <pre>
 *     314KGAAXX090507:1:19:1420:1123#0        16      chrM    3116    29      76M     *       *       *
 *          NNNNNNNNNNNNNNNNNTCCCCAATCCTCCAACGCATATAGCGGCCGCGCCTTCCCCCGTAAATGATATCATCTCA
 *          #################4?6/?2135;;;'1/=/<'B9;12;68?A79@,@==@9?=AAA3;A@B;A?B54;?ABA
 *     </pre>
 *
 *     Whereas with -CR WRITE_Q0S:
 *     <pre>
 *     314KGAAXX090507:1:19:1420:1123#0        16      chrM    3116    29      76M     *       *       *
 *          TAGGACCCGGGCCCCCCTCCCCAATCCTCCAACGCATATAGCGGCCGCGCCTTCCCCCGTAAATGATATCATCTCA
 *          !!!!!!!!!!!!!!!!!4?6/?2135;;;'1/=/<'B9;12;68?A79@,@==@9?=AAA3;A@B;A?B54;?ABA
 *     </pre>
 *
 *     Or -CR SOFTCLIP_BASES:
 *     <pre>
 *     314KGAAXX090507:1:19:1420:1123#0        16      chrM    3133    29      17S59M  *       *       *
 *          TAGGACCCGGGCCCCCCTCCCCAATCCTCCAACGCATATAGCGGCCGCGCCTTCCCCCGTAAATGATATCATCTCA
 *          #################4?6/?2135;;;'1/=/<'B9;12;68?A79@,@==@9?=AAA3;A@B;A?B54;?ABA
 *     </pre>
 * </p>
 *
 * <h2>Examples</h2>
 * <pre>
 *     -T ClipReads -I my.bam -I your.bam -o my_and_your.clipped.bam -R Homo_sapiens_assembly18.fasta \
 *     -XF seqsToClip.fasta -X CCCCC -CT "1-5,11-15" -QT 10
 * </pre>

 * @author Mark DePristo
 * @since 2010
 */
@Requires({DataSource.READS})
public class ClipReadsWalker extends ReadWalker<ClipReadsWalker.ReadClipperWithData, ClipReadsWalker.ClippingData> {
    /**
     * If provided, ClipReads will write summary statistics about the clipping operations applied
     * to the reads to this file.
     */
    @Output(fullName = "outputStatistics", shortName = "os", doc = "Write output statistics to this file", required = false)
    PrintStream out = null;

    /**
     * The output SAM/BAM file will be written here
     */
    @Output(doc = "Write BAM output here", required = true)
    StingSAMFileWriter outputBam;

    /**
     * If a value > 0 is provided, then the quality score based read clipper will be applied to the reads using this
     * quality score threshold.
     */
    @Argument(fullName = "qTrimmingThreshold", shortName = "QT", doc = "If provided, the Q-score clipper will be applied", required = false)
    int qTrimmingThreshold = -1;

    /**
     * Clips machine cycles from the read. Accepts a string of ranges of the form start1-end1,start2-end2, etc.
     * For each start/end pair, removes bases in machine cycles from start to end, inclusive. These are 1-based
     * values (positions). For example, 1-5,10-12 clips the first 5 bases, and then three bases at cycles 10, 11,
     * and 12.
     */
    @Argument(fullName = "cyclesToTrim", shortName = "CT", doc = "String indicating machine cycles to clip from the reads", required = false)
    String cyclesToClipArg = null;

    /**
     * Reads the sequences in the provided FASTA file, and clip any bases that exactly match any of the
     * sequences in the file.
     */
    @Argument(fullName = "clipSequencesFile", shortName = "XF", doc = "Remove sequences within reads matching the sequences in this FASTA file", required = false)
    String clipSequenceFile = null;

    /**
     * Clips bases from the reads matching the provided SEQ.  Can be provided any number of times on the command line
     */
    @Argument(fullName = "clipSequence", shortName = "X", doc = "Remove sequences within reads matching this sequence", required = false)
    String[] clipSequencesArgs = null;

    /**
     * The different values for this argument determines how ClipReads applies clips to the reads.  This can range
     * from writing Ns over the clipped bases to hard clipping away the bases from the BAM.
     */
    @Argument(fullName = "clipRepresentation", shortName = "CR", doc = "How should we actually clip the bases?", required = false)
    ClippingRepresentation clippingRepresentation = ClippingRepresentation.WRITE_NS;

    @Hidden
    @Advanced
    @Argument(fullName="read", doc="", required=false)
    String onlyDoRead = null;

    /**
     * List of sequence that should be clipped from the reads
     */
    List<SeqToClip> sequencesToClip = new ArrayList<SeqToClip>();

    /**
     * List of cycle start / stop pairs (0-based, stop is included in the cycle to remove) to clip from the reads
     */
    List<Pair<Integer, Integer>> cyclesToClip = null;

    /**
     * The initialize function.
     */
    public void initialize() {
        if (qTrimmingThreshold >= 0) {
            logger.info(String.format("Creating Q-score clipper with threshold %d", qTrimmingThreshold));
        }

        //
        // Initialize the sequences to clip
        //
        if (clipSequencesArgs != null) {
            int i = 0;
            for (String toClip : clipSequencesArgs) {
                i++;
                ReferenceSequence rs = new ReferenceSequence("CMDLINE-" + i, -1, StringUtil.stringToBytes(toClip));
                addSeqToClip(rs.getName(), rs.getBases());
            }
        }

        if (clipSequenceFile != null) {
            ReferenceSequenceFile rsf = ReferenceSequenceFileFactory.getReferenceSequenceFile(new File(clipSequenceFile));

            while (true) {
                ReferenceSequence rs = rsf.nextSequence();
                if (rs == null)
                    break;
                else {
                    addSeqToClip(rs.getName(), rs.getBases());
                }
            }
        }


        //
        // Initialize the cycle ranges to clip
        //
        if (cyclesToClipArg != null) {
            cyclesToClip = new ArrayList<Pair<Integer, Integer>>();
            for (String range : cyclesToClipArg.split(",")) {
                try {
                    String[] elts = range.split("-");
                    int start = Integer.parseInt(elts[0]) - 1;
                    int stop = Integer.parseInt(elts[1]) - 1;

                    if (start < 0) throw new Exception();
                    if (stop < start) throw new Exception();

                    logger.info(String.format("Creating cycle clipper %d-%d", start, stop));
                    cyclesToClip.add(new Pair<Integer, Integer>(start, stop));
                } catch (Exception e) {
                    throw new RuntimeException("Badly formatted cyclesToClip argument: " + cyclesToClipArg);
                }
            }
        }

        if (outputBam != null) {
            EnumSet<ClippingRepresentation> presorted = EnumSet.of(ClippingRepresentation.WRITE_NS, ClippingRepresentation.WRITE_NS_Q0S, ClippingRepresentation.WRITE_Q0S);
            outputBam.setPresorted(presorted.contains(clippingRepresentation));
        }
    }

    /**
     * Helper function that adds a seq with name and bases (as bytes) to the list of sequences to be clipped
     *
     * @param name
     * @param bases
     */
    private void addSeqToClip(String name, byte[] bases) {
        SeqToClip clip = new SeqToClip(name, StringUtil.bytesToString(bases));
        sequencesToClip.add(clip);
        logger.info(String.format("Creating sequence clipper %s: %s/%s", clip.name, clip.seq, clip.revSeq));
    }

    /**
     * The reads map function.
     *
     *
     * @param ref  the reference bases that correspond to our read, if a reference was provided
     * @param read the read itself, as a GATKSAMRecord
     * @return the ReadClipper object describing what should be done to clip this read
     */
    public ReadClipperWithData map(ReferenceContext ref, GATKSAMRecord read, ReadMetaDataTracker metaDataTracker) {
        if ( onlyDoRead == null || read.getReadName().equals(onlyDoRead) ) {
            if ( clippingRepresentation == ClippingRepresentation.HARDCLIP_BASES )
                read = (new ReadClipper(read)).revertSoftClippedBases();
            ReadClipperWithData clipper = new ReadClipperWithData(read, sequencesToClip);

            //
            // run all three clipping modules
            //
            clipBadQualityScores(clipper);
            clipCycles(clipper);
            clipSequences(clipper);
            return clipper;
        }

        return null;
    }

    /**
     * clip sequences from the reads that match all of the sequences in the global sequencesToClip variable.
     * Adds ClippingOps for each clip to clipper.
     *
     * @param clipper
     */
    private void clipSequences(ReadClipperWithData clipper) {
        if (sequencesToClip != null) {                // don't bother if we don't have any sequences to clip
            GATKSAMRecord read = clipper.getRead();
            ClippingData data = clipper.getData();

            for (SeqToClip stc : sequencesToClip) {
                // we have a pattern for both the forward and the reverse strands
                Pattern pattern = read.getReadNegativeStrandFlag() ? stc.revPat : stc.fwdPat;
                String bases = read.getReadString();
                Matcher match = pattern.matcher(bases);

                // keep clipping until match.find() says it can't find anything else
                boolean found = true;   // go through at least once
                while (found) {
                    found = match.find();
                    //System.out.printf("Matching %s against %s/%s => %b%n", bases, stc.seq, stc.revSeq, found);
                    if (found) {
                        int start = match.start();
                        int stop = match.end() - 1;
                        //ClippingOp op = new ClippingOp(ClippingOp.ClippingType.MATCHES_CLIP_SEQ, start, stop, stc.seq);
                        ClippingOp op = new ClippingOp(start, stop);
                        clipper.addOp(op);
                        data.incSeqClippedBases(stc.seq, op.getLength());
                    }
                }
            }
            clipper.setData(data);
        }
    }

    /**
     * Convenence function that takes a read and the start / stop clipping positions based on the forward
     * strand, and returns start/stop values appropriate for the strand of the read.
     *
     * @param read
     * @param start
     * @param stop
     * @return
     */
    private Pair<Integer, Integer> strandAwarePositions(GATKSAMRecord read, int start, int stop) {
        if (read.getReadNegativeStrandFlag())
            return new Pair<Integer, Integer>(read.getReadLength() - stop - 1, read.getReadLength() - start - 1);
        else
            return new Pair<Integer, Integer>(start, stop);
    }

    /**
     * clip bases at cycles between the ranges in cyclesToClip by adding appropriate ClippingOps to clipper.
     *
     * @param clipper
     */
    private void clipCycles(ReadClipperWithData clipper) {
        if (cyclesToClip != null) {
            GATKSAMRecord read = clipper.getRead();
            ClippingData data = clipper.getData();

            for (Pair<Integer, Integer> p : cyclesToClip) {   // iterate over each cycle range
                int cycleStart = p.first;
                int cycleStop = p.second;

                if (cycleStart < read.getReadLength()) {
                    // only try to clip if the cycleStart is less than the read's length
                    if (cycleStop >= read.getReadLength())
                        // we do tolerate [for convenience) clipping when the stop is beyond the end of the read
                        cycleStop = read.getReadLength() - 1;

                    Pair<Integer, Integer> startStop = strandAwarePositions(read, cycleStart, cycleStop);
                    int start = startStop.first;
                    int stop = startStop.second;

                    //ClippingOp op = new ClippingOp(ClippingOp.ClippingType.WITHIN_CLIP_RANGE, start, stop, null);
                    ClippingOp op = new ClippingOp(start, stop);
                    clipper.addOp(op);
                    data.incNRangeClippedBases(op.getLength());
                }
            }
            clipper.setData(data);
        }
    }

    /**
     * Clip bases from the read in clipper from
     * <p/>
     * argmax_x{ \sum{i = x + 1}^l (qTrimmingThreshold - qual)
     * <p/>
     * to the end of the read.  This is blatantly stolen from BWA.
     * <p/>
     * Walk through the read from the end (in machine cycle order) to the beginning, calculating the
     * running sum of qTrimmingThreshold - qual.  While we do this, we track the maximum value of this
     * sum where the delta > 0.  After the loop, clipPoint is either -1 (don't do anything) or the
     * clipping index in the read (from the end).
     *
     * @param clipper
     */
    private void clipBadQualityScores(ReadClipperWithData clipper) {
        GATKSAMRecord read = clipper.getRead();
        ClippingData data = clipper.getData();
        int readLen = read.getReadBases().length;
        byte[] quals = read.getBaseQualities();


        int clipSum = 0, lastMax = -1, clipPoint = -1; // -1 means no clip
        for (int i = readLen - 1; i >= 0; i--) {
            int baseIndex = read.getReadNegativeStrandFlag() ? readLen - i - 1 : i;
            byte qual = quals[baseIndex];
            clipSum += (qTrimmingThreshold - qual);
            if (clipSum >= 0 && (clipSum >= lastMax)) {
                lastMax = clipSum;
                clipPoint = baseIndex;
            }
        }

        if (clipPoint != -1) {
            int start = read.getReadNegativeStrandFlag() ? 0 : clipPoint;
            int stop = read.getReadNegativeStrandFlag() ? clipPoint : readLen - 1;
            //clipper.addOp(new ClippingOp(ClippingOp.ClippingType.LOW_Q_SCORES, start, stop, null));
            ClippingOp op = new ClippingOp(start, stop);
            clipper.addOp(op);
            data.incNQClippedBases(op.getLength());
        }
        clipper.setData(data);
    }

    /**
     * reduceInit is called once before any calls to the map function.  We use it here to setup the output
     * bam file, if it was specified on the command line
     *
     * @return
     */
    public ClippingData reduceInit() {
        return new ClippingData(sequencesToClip);
    }

    public ClippingData reduce(ReadClipperWithData clipper, ClippingData data) {
        if ( clipper == null )
            return data;

        GATKSAMRecord clippedRead = clipper.clipRead(clippingRepresentation);
        if (outputBam != null) {
            outputBam.addAlignment(clippedRead);
        } else {
            out.println(clippedRead.format());
        }

        data.nTotalReads++;
        data.nTotalBases += clipper.getRead().getReadLength();
        if (clipper.wasClipped()) {
            data.nClippedReads++;
            data.addData(clipper.getData());
        }
        return data;
    }

    public void onTraversalDone(ClippingData data) {
        if ( out != null )
            out.printf(data.toString());
    }

    // --------------------------------------------------------------------------------------------------------------
    //
    // utility classes
    //
    // --------------------------------------------------------------------------------------------------------------

    private static class SeqToClip {
        String name;
        String seq, revSeq;
        Pattern fwdPat, revPat;

        public SeqToClip(String name, String seq) {
            this.name = name;
            this.seq = seq;
            this.fwdPat = Pattern.compile(seq, Pattern.CASE_INSENSITIVE);
            this.revSeq = BaseUtils.simpleReverseComplement(seq);
            this.revPat = Pattern.compile(revSeq, Pattern.CASE_INSENSITIVE);
        }
    }

    public static class ClippingData {
        public long nTotalReads = 0;
        public long nTotalBases = 0;
        public long nClippedReads = 0;
        public long nClippedBases = 0;
        public long nQClippedBases = 0;
        public long nRangeClippedBases = 0;
        public long nSeqClippedBases = 0;

        HashMap<String, Long> seqClipCounts = new HashMap<String, Long>();

        public ClippingData(List<SeqToClip> clipSeqs) {
            for (SeqToClip clipSeq : clipSeqs) {
                seqClipCounts.put(clipSeq.seq, 0L);
            }
        }

        public void incNQClippedBases(int n) {
            nQClippedBases += n;
            nClippedBases += n;
        }

        public void incNRangeClippedBases(int n) {
            nRangeClippedBases += n;
            nClippedBases += n;
        }

        public void incSeqClippedBases(final String seq, int n) {
            nSeqClippedBases += n;
            nClippedBases += n;
            seqClipCounts.put(seq, seqClipCounts.get(seq) + n);
        }

        public void addData (ClippingData data) {
            nTotalReads += data.nTotalReads;
            nTotalBases += data.nTotalBases;
            nClippedReads += data.nClippedReads;
            nClippedBases += data.nClippedBases;
            nQClippedBases += data.nQClippedBases;
            nRangeClippedBases += data.nRangeClippedBases;
            nSeqClippedBases += data.nSeqClippedBases;

            for (String seqClip : data.seqClipCounts.keySet()) {
                Long count = data.seqClipCounts.get(seqClip);
                if (seqClipCounts.containsKey(seqClip))
                    count += seqClipCounts.get(seqClip);
                seqClipCounts.put(seqClip, count);
            }
        }

        public String toString() {
            StringBuilder s = new StringBuilder();

            s.append(Utils.dupString('-', 80) + "\n");
            s.append(String.format("Number of examined reads              %d%n", nTotalReads));
            s.append(String.format("Number of clipped reads               %d%n", nClippedReads));
            s.append(String.format("Percent of clipped reads              %.2f%n", (100.0 * nClippedReads) / nTotalReads));
            s.append(String.format("Number of examined bases              %d%n", nTotalBases));
            s.append(String.format("Number of clipped bases               %d%n", nClippedBases));
            s.append(String.format("Percent of clipped bases              %.2f%n", (100.0 * nClippedBases) / nTotalBases));
            s.append(String.format("Number of quality-score clipped bases %d%n", nQClippedBases));
            s.append(String.format("Number of range clipped bases         %d%n", nRangeClippedBases));
            s.append(String.format("Number of sequence clipped bases      %d%n", nSeqClippedBases));

            for (Map.Entry<String, Long> elt : seqClipCounts.entrySet()) {
                s.append(String.format("  %8d clip sites matching %s%n", elt.getValue(), elt.getKey()));
            }

            s.append(Utils.dupString('-', 80) + "\n");
            return s.toString();
        }
    }

    public class ReadClipperWithData extends ReadClipper {
        private ClippingData data;

        public ReadClipperWithData(GATKSAMRecord read, List<SeqToClip> clipSeqs) {
            super(read);
            data = new ClippingData(clipSeqs);
        }

        public ClippingData getData() {
            return data;
        }

        public void setData(ClippingData data) {
            this.data = data;
        }

        public void addData(ClippingData data) {
            this.data.addData(data);
        }
    }


}