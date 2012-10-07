package org.broadinstitute.sting.gatk.walkers.genotyper.afcalc;

import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Logger;
import org.apache.log4j.SimpleLayout;
import org.broadinstitute.sting.gatk.report.GATKReport;
import org.broadinstitute.sting.gatk.report.GATKReportTable;
import org.broadinstitute.sting.utils.SimpleTimer;
import org.broadinstitute.sting.utils.Utils;
import org.broadinstitute.sting.utils.variantcontext.Genotype;
import org.broadinstitute.sting.utils.variantcontext.VariantContext;
import org.broadinstitute.sting.utils.variantcontext.VariantContextBuilder;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.*;

/**
 * Created with IntelliJ IDEA.
 * User: depristo
 * Date: 10/2/12
 * Time: 10:25 AM
 * To change this template use File | Settings | File Templates.
 */
public class ExactAFCalculationPerformanceTest {
    final static Logger logger = Logger.getLogger(ExactAFCalculationPerformanceTest.class);

    private static abstract class Analysis {
        final GATKReport report;

        public Analysis(final String name, final List<String> columns) {
            report = GATKReport.newSimpleReport(name, columns);
        }

        public abstract void run(final ExactAFCalculationTestBuilder testBuilder,
                                 final List<Object> coreColumns);

        public String getName() {
            return getTable().getTableName();
        }

        public GATKReportTable getTable() {
            return report.getTables().iterator().next();
        }
    }

    private static class AnalyzeByACAndPL extends Analysis {
        public AnalyzeByACAndPL(final List<String> columns) {
            super("AnalyzeByACAndPL", Utils.append(columns, "non.type.pls", "ac", "n.alt.seg", "other.ac"));
        }

        public void run(final ExactAFCalculationTestBuilder testBuilder, final List<Object> coreValues) {
            final SimpleTimer timer = new SimpleTimer();

            for ( final int nonTypePL : Arrays.asList(100) ) {
                final ExactAFCalc calc = testBuilder.makeModel();
                final double[] priors = testBuilder.makePriors();

                for ( int[] ACs : makeACs(testBuilder.numAltAlleles, testBuilder.nSamples*2) ) {
                    final VariantContext vc = testBuilder.makeACTest(ACs, 0, nonTypePL);

                    timer.start();
                    final AFCalcResult result = calc.getLog10PNonRef(vc, priors);
                    final long runtime = timer.getElapsedTimeNano();

                    int otherAC = 0;
                    int nAltSeg = 0;
                    for ( int i = 0; i < ACs.length; i++ ) {
                        nAltSeg += ACs[i] > 0 ? 1 : 0;
                        if ( i > 0 ) otherAC += ACs[i];
                    }

                    final List<Object> columns = new LinkedList<Object>(coreValues);
                    columns.addAll(Arrays.asList(runtime, result.getnEvaluations(), nonTypePL, ACs[0], nAltSeg, otherAC));
                    report.addRowList(columns);
                }
            }
        }

        private List<int[]> makeACs(final int nAltAlleles, final int nChrom) {
            if ( nAltAlleles > 2 ) throw new IllegalArgumentException("nAltAlleles must be < 3");

            final List<int[]> ACs = new LinkedList<int[]>();

            if ( nAltAlleles == 1 )
                for ( int i = 0; i < nChrom; i++ ) {
                    ACs.add(new int[]{i});
            } else if ( nAltAlleles == 2 ) {
                for ( int i = 0; i < nChrom; i++ ) {
                    for ( int j : Arrays.asList(0, 1, 5, 10, 50, 100, 1000, 10000, 100000) ) {
                        if ( j < nChrom - i )
                            ACs.add(new int[]{i, j});
                    }
                }
            } else {
                throw new IllegalStateException("cannot get here");
            }

            return ACs;
        }
    }

    private static class AnalyzeBySingletonPosition extends Analysis {
        public AnalyzeBySingletonPosition(final List<String> columns) {
            super("AnalyzeBySingletonPosition", Utils.append(columns, "non.type.pls", "position.of.singleton"));
        }

        public void run(final ExactAFCalculationTestBuilder testBuilder, final List<Object> coreValues) {
            final SimpleTimer timer = new SimpleTimer();

            for ( final int nonTypePL : Arrays.asList(100) ) {
                final ExactAFCalc calc = testBuilder.makeModel();
                final double[] priors = testBuilder.makePriors();

                final int[] ac = new int[testBuilder.numAltAlleles];
                ac[0] = 1;
                final VariantContext vc = testBuilder.makeACTest(ac, 0, nonTypePL);

                for ( int position = 0; position < vc.getNSamples(); position++ ) {
                    final VariantContextBuilder vcb = new VariantContextBuilder(vc);
                    final List<Genotype> genotypes = new ArrayList<Genotype>(vc.getGenotypes());
                    Collections.rotate(genotypes, position);
                    vcb.genotypes(genotypes);

                    timer.start();
                    final AFCalcResult result = calc.getLog10PNonRef(vcb.make(), priors);
                    final long runtime = timer.getElapsedTimeNano();

                    final List<Object> columns = new LinkedList<Object>(coreValues);
                    columns.addAll(Arrays.asList(runtime, result.getnEvaluations(), nonTypePL, position));
                    report.addRowList(columns);
                }
            }
        }
    }

    private static class AnalyzeByNonInformative extends Analysis {
        public AnalyzeByNonInformative(final List<String> columns) {
            super("AnalyzeByNonInformative", Utils.append(columns, "non.type.pls", "n.non.informative"));
        }

        public void run(final ExactAFCalculationTestBuilder testBuilder, final List<Object> coreValues) {
            final SimpleTimer timer = new SimpleTimer();

            for ( final int nonTypePL : Arrays.asList(100) ) {
                final ExactAFCalc calc = testBuilder.makeModel();
                final double[] priors = testBuilder.makePriors();

                final int[] ac = new int[testBuilder.numAltAlleles];
                ac[0] = 1;

                for ( int nNonInformative = 0; nNonInformative < testBuilder.nSamples; nNonInformative++ ) {
                    final VariantContext vc = testBuilder.makeACTest(ac, nNonInformative, nonTypePL);

                    timer.start();
                    final AFCalcResult result = calc.getLog10PNonRef(vc, priors);
                    final long runtime = timer.getElapsedTimeNano();

                    final List<Object> columns = new LinkedList<Object>(coreValues);
                    columns.addAll(Arrays.asList(runtime, result.getnEvaluations(), nonTypePL, nNonInformative));
                    report.addRowList(columns);
                }
            }
        }
    }

    private static class ModelParams {
        final ExactAFCalculationTestBuilder.ModelType modelType;
        final int maxBiNSamples, maxTriNSamples;

        private ModelParams(ExactAFCalculationTestBuilder.ModelType modelType, int maxBiNSamples, int maxTriNSamples) {
            this.modelType = modelType;
            this.maxBiNSamples = maxBiNSamples;
            this.maxTriNSamples = maxTriNSamples;
        }

        public boolean meetsConstraints(final int nAltAlleles, final int nSamples) {
            if ( nAltAlleles == 1 )
                return nSamples <= maxBiNSamples;
            else if ( nAltAlleles == 2 )
                return nSamples <= maxTriNSamples;
            else
                throw new IllegalStateException("Unexpected number of alt alleles " + nAltAlleles);
        }
    }

    public static void main(final String[] args) throws Exception {
        logger.addAppender(new ConsoleAppender(new SimpleLayout()));

        final List<String> coreColumns = Arrays.asList("iteration", "n.alt.alleles", "n.samples",
                "exact.model", "prior.type", "runtime", "n.evaluations");

        final PrintStream out = new PrintStream(new FileOutputStream(args[0]));

        final List<ModelParams> modelParams = Arrays.asList(
                new ModelParams(ExactAFCalculationTestBuilder.ModelType.ReferenceDiploidExact, 1000, 10),
//                new ModelParams(ExactAFCalculationTestBuilder.ModelType.GeneralExact, 100, 10),
                new ModelParams(ExactAFCalculationTestBuilder.ModelType.ConstrainedDiploidExact, 1000, 100),
                new ModelParams(ExactAFCalculationTestBuilder.ModelType.IndependentDiploidExact, 1000, 10000));

        final boolean ONLY_HUMAN_PRIORS = false;
        final List<ExactAFCalculationTestBuilder.PriorType> priorTypes = ONLY_HUMAN_PRIORS
                ? Arrays.asList(ExactAFCalculationTestBuilder.PriorType.values())
                : Arrays.asList(ExactAFCalculationTestBuilder.PriorType.human);

        final List<Analysis> analyzes = new ArrayList<Analysis>();
        analyzes.add(new AnalyzeByACAndPL(coreColumns));
        analyzes.add(new AnalyzeBySingletonPosition(coreColumns));
        //analyzes.add(new AnalyzeByNonInformative(coreColumns));

        for ( int iteration = 0; iteration < 1; iteration++ ) {
            for ( final int nAltAlleles : Arrays.asList(1, 2) ) {
                for ( final int nSamples : Arrays.asList(1, 10, 100, 1000, 10000) ) {
                        for ( final ModelParams modelToRun : modelParams) {
                            if ( modelToRun.meetsConstraints(nAltAlleles, nSamples) ) {
                                for ( final ExactAFCalculationTestBuilder.PriorType priorType : priorTypes ) {
                                final ExactAFCalculationTestBuilder testBuilder
                                        = new ExactAFCalculationTestBuilder(nSamples, nAltAlleles, modelToRun.modelType, priorType);

                                for ( final Analysis analysis : analyzes ) {
                                    logger.info(Utils.join("\t", Arrays.asList(iteration, nAltAlleles, nSamples, modelToRun.modelType, priorType, analysis.getName())));
                                    final List<?> values = Arrays.asList(iteration, nAltAlleles, nSamples, modelToRun.modelType, priorType);
                                    analysis.run(testBuilder, (List<Object>)values);
                                }
                            }
                        }
                    }
                }
            }
        }

        final GATKReport report = new GATKReport();
        for ( final Analysis analysis : analyzes )
            report.addTable(analysis.getTable());
        report.print(out);
        out.close();
    }
}