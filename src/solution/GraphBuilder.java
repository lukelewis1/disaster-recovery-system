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

        // the SAXBuilder is the easiest way to create the JDOM2 objects.
        SAXBuilder jdomBuilder = new SAXBuilder();

        // jdomDocument is the JDOM2 Object
        Document jdomDocument = jdomBuilder.build(file);

        // The root element is the root of the document.
        Element graphxml = jdomDocument.getRootElement();

        Namespace ns = graphxml.getNamespace(); // Namespace.getNamespace("http://foo.com");

        Element graph = graphxml.getChild("graph", ns);

        List<Element> nodes = graph.getChildren("node", ns);

        for (Element e: nodes) {
            String id = e.getAttribute("id").getValue();
            g.addNode(id);
        }

        List<Element> edges = graph.getChildren("edge", ns);

        for (Element e : edges) {
            List<Attribute> at = e.getAttributes();
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

