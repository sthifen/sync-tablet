package android.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/** XmlPullParser sobre StAX (javax.xml.stream). */
public final class Xml {
    public static XmlPullParser newPullParser() { return new Parser(); }

    private static final class Parser implements XmlPullParser {
        private XMLStreamReader r;
        private int event = START_DOCUMENT;
        private int depth;
        private String name, text;
        private final ArrayList<String[]> attrs = new ArrayList<>();

        private static XMLInputFactory factory() {
            XMLInputFactory f = XMLInputFactory.newInstance();
            f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            f.setProperty(XMLInputFactory.IS_COALESCING, true);
            return f;
        }

        @Override public void setFeature(String n, boolean s) {}

        @Override public void setInput(InputStream in, String enc) throws XmlPullParserException {
            try { r = enc == null ? factory().createXMLStreamReader(in) : factory().createXMLStreamReader(in, enc); }
            catch (XMLStreamException e) { throw new XmlPullParserException(e.getMessage(), this, e); }
            event = START_DOCUMENT; depth = 0;
        }

        @Override public void setInput(Reader in) throws XmlPullParserException {
            try { r = factory().createXMLStreamReader(in); }
            catch (XMLStreamException e) { throw new XmlPullParserException(e.getMessage(), this, e); }
            event = START_DOCUMENT; depth = 0;
        }

        @Override public int getEventType() { return event; }

        @Override public int next() throws XmlPullParserException, IOException {
            if (event == END_TAG) depth--;
            try {
                while (r.hasNext()) {
                    int e = r.next();
                    switch (e) {
                        case XMLStreamConstants.START_ELEMENT:
                            name = r.getLocalName();
                            attrs.clear();
                            for (int i = 0; i < r.getAttributeCount(); i++) attrs.add(new String[] {r.getAttributeLocalName(i), r.getAttributeValue(i)});
                            depth++;
                            text = null;
                            return event = START_TAG;
                        case XMLStreamConstants.END_ELEMENT:
                            name = r.getLocalName();
                            attrs.clear();
                            text = null;
                            return event = END_TAG;
                        case XMLStreamConstants.CHARACTERS:
                        case XMLStreamConstants.CDATA:
                        case XMLStreamConstants.SPACE:
                            text = r.getText();
                            name = null;
                            return event = TEXT;
                        case XMLStreamConstants.END_DOCUMENT:
                            return event = END_DOCUMENT;
                        default:
                    }
                }
            } catch (XMLStreamException e) {
                throw new XmlPullParserException(e.getMessage(), this, e);
            }
            return event = END_DOCUMENT;
        }

        @Override public String getName() { return name; }
        @Override public String getText() { return text; }

        @Override public String nextText() throws XmlPullParserException, IOException {
            if (event != START_TAG) throw new XmlPullParserException("nextText: no está en START_TAG");
            int e = next();
            if (e == TEXT) {
                StringBuilder b = new StringBuilder(text);
                while ((e = next()) == TEXT) b.append(text);
                if (e != END_TAG) throw new XmlPullParserException("nextText: se esperaba END_TAG");
                return b.toString();
            }
            if (e == END_TAG) return "";
            throw new XmlPullParserException("nextText: se esperaba texto");
        }

        @Override public String getAttributeValue(String ns, String n) {
            for (String[] a : attrs) if (a[0].equals(n)) return a[1];
            return null;
        }

        @Override public int getAttributeCount() { return event == START_TAG ? attrs.size() : -1; }
        @Override public String getAttributeName(int i) { return attrs.get(i)[0]; }
        @Override public String getAttributeValue(int i) { return attrs.get(i)[1]; }
        @Override public int getDepth() { return depth; }
    }

    private Xml() {}
}
