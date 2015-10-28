package doser.entitydisambiguation.algorithms.collective;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import doser.word2vec.Data;
import doser.word2vec.Doc2VecJsonFormat;
import doser.word2vec.Word2VecJsonFormat;

/**
 * Class holding Word2Vec and Doc2Vec information for collective disambiguation
 * 
 * @author quh
 *
 */
public class Word2Vec {

	protected List<SurfaceForm> repList;

	protected Map<String, Float> word2vecsimilarities;

	protected Map<String, Float> doc2vecsimilarities;

	public Word2Vec(List<SurfaceForm> rep) {
		super();
		this.repList = rep;
		this.computeWord2VecSimilarities(rep);
//		this.computeLocalContextCompatibility(rep);
	}

	protected float getWord2VecSimilarity(String source, String target) {
		source = source.replaceAll("http://dbpedia.org/resource/", "");
		target = target.replaceAll("http://dbpedia.org/resource/", "");
		int c = source.compareToIgnoreCase(target);
		String res = "";
		if (c < 0) {
			res = source + "|" + target;
		} else if (c == 0) {
			res = source + "|" + target;
		} else {
			res = target + "|" + source;
		}

		float result = 0;
		if (this.word2vecsimilarities.containsKey(res)) {
			result = this.word2vecsimilarities.get(res) + 1.0f;
		}
		return result;
	}

	protected float getWord2VecSimilarity(List<String> source, String target) {
		target = target.replaceAll("http://dbpedia.org/resource/", "");
		Collections.sort(source);
		String res = "";
		for (String s : source) {
			res += s.replaceAll("http://dbpedia.org/resource/", "") + "|";
		}
		res += target;
		float result = 0;
		if (this.word2vecsimilarities.containsKey(res)) {
			result = this.word2vecsimilarities.get(res) + 1.0f;
		}
		return result;
	}

	protected float getDoc2VecSimilarity(String sf, String context,
			String entity) {
		String key = sf + context + entity;
		if (this.doc2vecsimilarities.containsKey(key)) {
			return this.doc2vecsimilarities.get(key) + 1.0f;
		} else {
			return 0;
		}
	}

	private void computeLocalContextCompatibility(
			List<SurfaceForm> rep) {
		this.doc2vecsimilarities = new HashMap<String, Float>();
		Doc2VecJsonFormat format = new Doc2VecJsonFormat();
		for (SurfaceForm sf : rep) {
			String context = sf.getContext();
			
			context = context.toLowerCase();
			context = context.replaceAll("[\\.\\,\\!\\? ]+", " ");
			
			Data doc = new Data();
			String[] candidates = new String[sf.getCandidates().size()];
			sf.getCandidates().toArray(candidates);
			doc.setCandidates(candidates);
			doc.setContext(context);
			doc.setSurfaceForm(sf.getSurfaceForm());
			doc.getQryNr();
			format.addData(doc);
		}
		JSONArray res = Word2VecJsonFormat.performquery(format, "d2vsim");

		// We obtain the same order of surface forms
		for (int i = 0; i < res.length(); i++) {
			SurfaceForm c = rep.get(i);
			try {
				JSONObject obj = res.getJSONObject(i);
				JSONArray simArray = obj.getJSONArray("sim");
				for (int j = 0; j < simArray.length(); j++) {
					float sim = (float) simArray.getDouble(j);
					String entity = c.getCandidates().get(j);
					this.doc2vecsimilarities.put(
							c.getSurfaceForm() + c.getContext() + entity, sim);
					// c.setCandidateCompatibility(entity, sim);
				}
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * Basically, this method works but is not as fast as computing word2vec
	 * similarities within Java. However, we apply this method because we do not
	 * have to load the word2vec model in memory at Tomcat startup. Another
	 * advantage is the decoupling of word2vec queries in our Knowledgebase
	 * interface.
	 * 
	 * @param rep
	 */
	private void computeWord2VecSimilarities(
			List<SurfaceForm> rep) {
		this.word2vecsimilarities = new HashMap<String, Float>();
		Set<String> entities = new HashSet<String>();
		List<String> multientities = new LinkedList<String>();
		for (SurfaceForm r : rep) {
			List<String> l = r.getCandidates();
			for (String s : l) {
				if (l.size() == 1) {
					multientities.add(s.replaceAll(
							"http://dbpedia.org/resource/", ""));
				}
				entities.add(s.replaceAll("http://dbpedia.org/resource/", ""));
			}
		}
		Collections.sort(multientities);
		StringBuilder builder = new StringBuilder();
		for (String s : multientities) {
			builder.append(s + "|");
		}
		String ent = builder.toString();
		if (ent.length() > 0) {
			ent = ent.substring(0, ent.length() - 1);
			entities.add(ent);
		}

		Set<String> combinations = new HashSet<String>();
		for (String e1 : entities) {
			for (String e2 : entities) {
				int nr1 = e1.split("\\|").length;
				int nr2 = e2.split("\\|").length;
				if (nr1 > 1 || nr2 > 1) {
					if (nr1 > 1 && nr1 == 1) {
						combinations.add(e1 + "|" + e2);
					} else if (nr2 > 1 && nr1 == 1) {
						combinations.add(e2 + "|" + e1);
					}
				} else {
					int c = e1.compareToIgnoreCase(e2);
					if (c < 0) {
						combinations.add(e1 + "|" + e2);
					} else if (c == 0) {
						combinations.add(e1 + "|" + e2);
					} else {
						combinations.add(e2 + "|" + e1);
					}
				}
			}
		}
		Word2VecJsonFormat format = new Word2VecJsonFormat();
		format.setData(combinations);
		JSONArray res = Word2VecJsonFormat.performquery(format, "w2vsim");
		for (int i = 0; i < res.length(); i++) {
			try {
				JSONObject obj = res.getJSONObject(i);
				String ents = obj.getString("ents");
				float sim = (float) obj.getDouble("sim");
				this.word2vecsimilarities.put(ents, sim);
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}
	}

	public static void main(String[] args) {
		String test = "My name is Stefan  .?!";
		System.out.println(test.replaceAll("[\\.\\,\\!\\? ]+", " "));
	}
}
