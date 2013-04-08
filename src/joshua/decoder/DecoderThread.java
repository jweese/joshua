package joshua.decoder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import joshua.decoder.chart_parser.Chart;
import joshua.decoder.ff.FeatureFunction;
import joshua.decoder.ff.FeatureVector;
import joshua.decoder.ff.SourceDependentFF;
import joshua.decoder.ff.state_maintenance.StateComputer;
import joshua.decoder.ff.tm.Grammar;
import joshua.decoder.ff.tm.GrammarFactory;
import joshua.decoder.hypergraph.ForestWalker;
import joshua.decoder.hypergraph.GrammarBuilderWalkerFunction;
import joshua.decoder.hypergraph.HyperGraph;
import joshua.decoder.segment_file.Sentence;
import joshua.corpus.Vocabulary;

/**
 * This class handles decoding of individual Sentence objects (which can represent plain sentences
 * or lattices). A single sentence can be decoded by a call to translate() and, if an InputHandler
 * is used, many sentences can be decoded in a thread-safe manner via a single call to
 * translateAll(), which continually queries the InputHandler for sentences until they have all been
 * consumed and translated.
 * 
 * The DecoderFactory class is responsible for launching the threads.
 * 
 * @author Matt Post <post@cs.jhu.edu>
 * @author Zhifei Li, <zhifei.work@gmail.com>
 */

public class DecoderThread extends Thread {
  /*
   * these variables may be the same across all threads (e.g., just copy from DecoderFactory), or
   * differ from thread to thread
   */
  private final List<GrammarFactory> grammarFactories;
  private final List<FeatureFunction> featureFunctions;
  private final List<StateComputer> stateComputers;

  private static final Logger logger = Logger.getLogger(DecoderThread.class.getName());

  // ===============================================================
  // Constructor
  // ===============================================================
  public DecoderThread(List<GrammarFactory> grammarFactories, FeatureVector weights,
      List<FeatureFunction> featureFunctions, List<StateComputer> stateComputers)
      throws IOException {

    this.grammarFactories = grammarFactories;
    this.stateComputers = stateComputers;

    this.featureFunctions = new ArrayList<FeatureFunction>();
    for (FeatureFunction ff : featureFunctions) {
      if (ff instanceof SourceDependentFF) {
        this.featureFunctions.add(((SourceDependentFF) ff).clone());
      } else {
        this.featureFunctions.add(ff);
      }
    }
  }

  // ===============================================================
  // Methods
  // ===============================================================

  @Override
  public void run() {
    // Nothing to do but wait.
  }

  /**
   * Translate a sentence.
   * 
   * @param sentence The sentence to be translated.
   */
  public Translation translate(Sentence sentence) {

    logger.info("Translating sentence #" + sentence.id() + " [thread " + getId() + "]\n"
        + sentence.source());
    if (sentence.target() != null)
      logger.info("Constraining to target sentence '" + sentence.target() + "'");

    // skip blank sentences
    if (sentence.isEmpty()) {
      logger.info("translation of sentence " + sentence.id() + " took 0 seconds [" + getId() + "]");
      return new Translation(sentence, null, featureFunctions);
    }

    long startTime = System.currentTimeMillis();

    int numGrammars = grammarFactories.size();
    Grammar[] grammars = new Grammar[numGrammars];

    for (int i = 0; i < grammarFactories.size(); i++)
      grammars[i] = grammarFactories.get(i).getGrammarForSentence(sentence);

    /* Seeding: the chart only sees the grammars, not the factories */
    Chart chart = new Chart(sentence, this.featureFunctions, this.stateComputers, grammars,
        JoshuaConfiguration.goal_symbol);

    /* Parsing */
    HyperGraph hypergraph = null;
    try {
      hypergraph = chart.expand();
    } catch (java.lang.OutOfMemoryError e) {
      logger.warning(String.format("sentence %d: out of memory", sentence.id()));
      hypergraph = null;
    }

    float seconds = (System.currentTimeMillis() - startTime) / 1000.0f;
    logger.info(String.format("translation of sentence %d took %.3f seconds [thread %d]",
        sentence.id(), seconds, getId()));
    logger.info(String.format("Memory used after sentence %d is %.1f MB", sentence.id(), (Runtime
        .getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1000000.0));

    if (!JoshuaConfiguration.parse) {
      return new Translation(sentence, hypergraph, featureFunctions);
    }

		boolean isSuccessfulParse = true;
		if (hypergraph == null) {
			hypergraph = chart.hypergraphRootedAtLongestCompleteSpan();
			isSuccessfulParse = false;
		}
		/*
     * Synchronous parsing.
     * 
     * Step 1. Traverse the hypergraph to create a grammar for the second-pass parse.
     */
		Grammar newGrammar;
		if (isSuccessfulParse) {
			newGrammar = getGrammarFromHyperGraph(JoshuaConfiguration.goal_symbol, hypergraph);
		} else {
			final int sourceLength = sentence.intLattice().size() - 1;
			newGrammar = getGrammarFromChart(JoshuaConfiguration.goal_symbol, chart, sourceLength);
		}
    newGrammar.sortGrammar(this.featureFunctions);
    long sortTime = System.currentTimeMillis();
    logger.info(String.format("Sentence %d: New grammar has %d rules.", sentence.id(),
        newGrammar.getNumRules()));

    /* Step 2. Create a new chart and parse with the instantiated grammar. */
    Grammar[] newGrammarArray = new Grammar[] { newGrammar };
    Sentence targetSentence = new Sentence(sentence.target(), sentence.id());
    chart = new Chart(targetSentence, featureFunctions, stateComputers, newGrammarArray, "GOAL");
    int goalSymbol = GrammarBuilderWalkerFunction.goalSymbol(hypergraph, isSuccessfulParse);
    String goalSymbolString = Vocabulary.word(goalSymbol);
    logger.info(String.format("Sentence %d: goal symbol is %s (%d).", sentence.id(),
        goalSymbolString, goalSymbol));
    chart.setGoalSymbolID(goalSymbol);

    /* Parsing */
    HyperGraph englishParse = chart.expand();
    long secondParseTime = System.currentTimeMillis();
    logger.info(String.format("Sentence %d: Finished second chart expansion (%d seconds).",
        sentence.id(), (secondParseTime - sortTime) / 1000));
    logger.info(String.format("Sentence %d total time: %d seconds.\n", sentence.id(),
        (secondParseTime - startTime) / 1000));
    logger.info(String.format("Memory used after sentence %d is %.1f MB", sentence.id(), (Runtime
        .getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1000000.0));
		if (englishParse == null) {
			englishParse = chart.hypergraphRootedAtLongestCompleteSpan();
		}

    return new Translation(sentence, englishParse, featureFunctions); // or do something else
  }

  private static Grammar getGrammarFromHyperGraph(String goal, HyperGraph hg) {
    GrammarBuilderWalkerFunction f = new GrammarBuilderWalkerFunction(goal);
    ForestWalker walker = new ForestWalker();
    walker.walk(hg.goalNode, f);
    return f.getGrammar();
  }

	private static Grammar getGrammarFromChart(String goal, Chart chart, int sourceLength) {
    GrammarBuilderWalkerFunction f = new GrammarBuilderWalkerFunction(goal);
    ForestWalker walker = new ForestWalker();
		for (int width = sourceLength; width > 0; width--) {
			for (int i = 0; i <= sourceLength - width; i++) {
				final int j = i + width;
				HyperGraph hg = chart.hypergraphRootedAtCell(i, j);
				if (hg != null) {
					walker.walk(hg.goalNode, f);
				}
			}
		}
    return f.getGrammar();
	}
}
