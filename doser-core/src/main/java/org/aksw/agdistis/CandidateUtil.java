package org.aksw.agdistis;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;

import org.aksw.agdistis.datatypes.Document;
import org.aksw.agdistis.datatypes.NamedEntitiesInText;
import org.aksw.agdistis.datatypes.NamedEntityInText;
import org.aksw.agdistis.util.Triple;
import org.aksw.agdistis.util.TripleIndex;
import org.apache.lucene.search.spell.NGramDistance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.uci.ics.jung.graph.DirectedSparseGraph;

public class CandidateUtil {
	private static Logger log = LoggerFactory.getLogger(CandidateUtil.class);
	private String nodeType;
	private TripleIndex index;
	private NGramDistance nGramDistance;
	private CorporationAffixCleaner corporationAffixCleaner;
	private DomainWhiteLister domainWhiteLister;
	
	
    private static volatile CandidateUtil instance;
    private CandidateUtil() throws IOException {
		this.nodeType = "http://dbpedia.org/resource/";
		this.nGramDistance = new NGramDistance(3);

		this.index = new TripleIndex();
		this.corporationAffixCleaner = new CorporationAffixCleaner();
		this.domainWhiteLister = new DomainWhiteLister(index);
    	
    }

    public static synchronized CandidateUtil getInstance() throws IOException {
        if (instance == null ) {
            synchronized (CandidateUtil.class) {
                if (instance == null) {
                    instance = new CandidateUtil();
                }
            }
        }

        return instance;
    }
	

//	public CandidateUtil() throws IOException {
////		Properties prop = new Properties();
////		InputStream input = CandidateUtil.class
////				.getResourceAsStream("/home/quh/Arbeitsfläche/Git/AGDISTIS/src/main/resources/config/agdistis.de.properties");
////		prop.load(input);
//
//
//	}

	public void insertCandidatesIntoText(
			DirectedSparseGraph<Node, String> graph, Document document,
			double threshholdTrigram, Boolean heuristicExpansionOn) {
		NamedEntitiesInText namedEntities = document.getNamedEntitiesInText();
		String text = document.DocumentText().getText();

		HashMap<String, Node> nodes = new HashMap<String, Node>();

		// used for heuristic label expansion start with longest Named Entities
		Collections.sort(namedEntities.getNamedEntities(),
				new NamedEntityLengthComparator());
		Collections.reverse(namedEntities.getNamedEntities());
		HashSet<String> heuristicExpansion = new HashSet<String>();
		for (NamedEntityInText entity : namedEntities) {
			String label = text.substring(entity.getStartPos(),
					entity.getEndPos());
			log.info("\tLabel: " + label);
			long start = System.currentTimeMillis();

			if (heuristicExpansionOn) {
				label = heuristicExpansion(heuristicExpansion, label);
			}
			checkLabelCandidates(graph, threshholdTrigram, nodes, entity,
					label, false);

			System.out.println(("\tGraph size: " + graph.getVertexCount() + " took: "
					+ (System.currentTimeMillis() - start) + " ms"));
		}
	}

	private String heuristicExpansion(HashSet<String> heuristicExpansion,
			String label) {
		String tmp = label;
		boolean expansion = false;
		for (String key : heuristicExpansion) {
			if (key.contains(label)) {
				// take the shortest possible expansion
				if (tmp.length() > key.length() && tmp != label) {
					tmp = key;
					expansion = true;
					log.debug("Heuristic expansion: " + label + "-->" + key);
				}
				if (tmp.length() < key.length() && tmp == label) {
					tmp = key;
					expansion = true;
					log.debug("Heuristic expansion: " + label + "-->" + key);
				}
			}
		}
		label = tmp;
		if (!expansion) {
			heuristicExpansion.add(label);
		}
		return label;
	}

	public void addNodeToGraph(DirectedSparseGraph<Node, String> graph,
			HashMap<String, Node> nodes, NamedEntityInText entity, Triple c,
			String candidateURL) {
		Node currentNode = new Node(candidateURL, 0, 0);
		log.debug("CandidateURL: " + candidateURL);
		// candidates are connected to a specific label in the text via their
		// start position
		if (!graph.addVertex(currentNode)) {
			int st = entity.getStartPos();
			if (nodes.get(candidateURL) != null) {
				nodes.get(candidateURL).addId(st);
			} else {
				log.error("This vertex couldn't be added because of an bug in Jung: "
						+ candidateURL);
			}
		} else {
			currentNode.addId(entity.getStartPos());
			nodes.put(candidateURL, currentNode);
		}
	}

	private void checkLabelCandidates(DirectedSparseGraph<Node, String> graph,
			double threshholdTrigram, HashMap<String, Node> nodes,
			NamedEntityInText entity, String label, boolean searchInSurfaceForms) {
		label = corporationAffixCleaner
				.cleanLabelsfromCorporationIdentifier(label);
		label = label.trim();

		List<Triple> candidates = new ArrayList<Triple>();
		candidates = searchCandidatesByLabel(label, searchInSurfaceForms);
		log.info("\t\tnumber of candidates: " + candidates.size());
		if (candidates.size() == 0) {
			log.info("\t\t\tNo candidates for: " + label);
			if (label.endsWith("'s")) {
				// removing plural s
				label = label.substring(0, label.lastIndexOf("'s"));
				candidates = searchCandidatesByLabel(label,
						searchInSurfaceForms);
				log.info("\t\t\tEven not with expansion");
			} else if (label.endsWith("s")) {
				// removing genitiv s
				label = label.substring(0, label.lastIndexOf("s"));
				candidates = searchCandidatesByLabel(label,
						searchInSurfaceForms);
				log.info("\t\t\tEven not with expansion");
			}
		}
		boolean added = false;
		for (Triple c : candidates) {
			log.debug("Candidate triple to check: " + c);
			String candidateURL = c.getSubject();
			String surfaceForm = c.getObject();
			// rule of thumb: no year numbers in candidates
			if (candidateURL.startsWith(nodeType)
					&& !candidateURL.matches("[0-9][0-9]")) {
				// trigram similarity
				if (nGramDistance.getDistance(surfaceForm, label) < threshholdTrigram) {
					continue;
				}
				// iff it is a disambiguation resource, skip it
				if (isDisambiguationResource(candidateURL)) {
					continue;
				}
				// follow redirect
				candidateURL = redirect(candidateURL);
				if (domainWhiteLister.fitsIntoDomain(candidateURL)) {
					addNodeToGraph(graph, nodes, entity, c, candidateURL);
					added = true;
				}
			}
		}
		if (!added && !searchInSurfaceForms)
			checkLabelCandidates(graph, threshholdTrigram, nodes, entity,
					label, true);
	}

	private ArrayList<Triple> searchCandidatesByLabel(String label,
			boolean searchInSurfaceFormsToo) {
		ArrayList<Triple> tmp = new ArrayList<Triple>();
		tmp.addAll(index.search(null,
				"http://www.w3.org/2000/01/rdf-schema#label", label));
		if (searchInSurfaceFormsToo) {
			tmp.addAll(index.search(null,
					"http://www.w3.org/2004/02/skos/core#altLabel", label));
		}
		return tmp;
	}

	private boolean isDisambiguationResource(String candidateURL) {
		List<Triple> tmp = index.search(candidateURL,
				"http://dbpedia.org/ontology/wikiPageDisambiguates", null);
		if (tmp.isEmpty())
			return false;
		else
			return true;
	}

	private String redirect(String candidateURL) {
		if (candidateURL == null) {
			return candidateURL;
		}
		List<Triple> redirect = index.search(candidateURL,
				"http://dbpedia.org/ontology/wikiPageRedirects", null);
		if (redirect.size() == 1) {
			return redirect.get(0).getObject();
		} else if (redirect.size() > 1) {
			log.error("Several redirects detected for :" + candidateURL);
			return candidateURL;
		} else {
			return candidateURL;
		}
	}

	public void close() throws IOException {
		index.close();
	}

	public TripleIndex getIndex() {
		return index;
	}
}
