package solution;

import org.jdom2.*;
import org.jdom2.input.SAXBuilder;

import java.io.IOException;
import java.util.List;

/**
 *
 * @author lewi0146, leib0006
 */
public class GraphBuilder {

    public static Graph buildFromGraphML(String file) throws JDOMException, IOException {
        Graph g = new Graph();

        SAXBuilder jdomBuilder = new SAXBuilder();
        Document jdomDocument = jdomBuilder.build(file);

        Element graphxml = jdomDocument.getRootElement();
        Namespace ns = graphxml.getNamespace();
        Element graph = graphxml.getChild("graph", ns);

        // Nodes: one per <node id="...">.
        List<Element> nodes = graph.getChildren("node", ns);
        for (Element e: nodes) {
            String id = e.getAttribute("id").getValue();
            g.addNode(id);
        }

        // Edges: directed source→target, weight from the d1 data element.
        List<Element> edges = graph.getChildren("edge", ns);
        for (Element e : edges) {
            long nS = e.getAttribute("source").getLongValue();
            long nD = e.getAttribute("target").getLongValue();

            List<Element> datas = e.getChildren("data",ns);
            for (Element d: datas) {
                if (d.getAttribute("key").getValue().equals("d1")) {
                    double dist = Double.parseDouble(d.getText());
                    g.addEdge(String.valueOf(nS), String.valueOf(nD), dist);
                }
            }
        }
        return g;
    }

}

