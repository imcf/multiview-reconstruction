package net.preibisch.legacy.io;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.w3c.dom.Node;
import org.w3c.dom.Element;
import java.io.File;
import net.preibisch.legacy.io.IOFunctions;


public class ReadXMLValue
{
    public static String defaultXMLpath = "";

    String path = defaultXMLpath;

    public ReadXMLValue( final String path ) { this.path = path; }

    public String getValue( final String tag, final Integer view_idx )
    {
        String value = "";
        try
        {
            //creating a constructor of file class and parsing an XML file
            //an instance of factory that gives a document builder
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            //an instance of builder to parse the specified xml file
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(path);
            doc.getDocumentElement().normalize();
            // System.out.println("Root element: " + doc.getDocumentElement().getNodeName());
            NodeList nodeList = doc.getElementsByTagName("ViewSetup");
            // nodeList is not iterable, so we are using for loop

            Node node = nodeList.item(view_idx);
            // System.out.println("\nNode Name :" + node.getNodeName());
            if (node.getNodeType() == Node.ELEMENT_NODE)
            {
                Element eElement = (Element) node;
                NodeList listDist = eElement.getElementsByTagName(tag);
                value = listDist.item(listDist.getLength() - 1 ).getTextContent();
                // IOFunctions.println("Tag: "+ tag + " value: " + value);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        return value;

    }
}