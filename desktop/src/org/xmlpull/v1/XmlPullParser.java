package org.xmlpull.v1;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;

/** Lo que usa la app del analizador XML de Android. */
public interface XmlPullParser {
    int START_DOCUMENT = 0, END_DOCUMENT = 1, START_TAG = 2, END_TAG = 3, TEXT = 4;
    String FEATURE_PROCESS_NAMESPACES = "http://xmlpull.org/v1/doc/features.html#process-namespaces";

    void setFeature(String name, boolean state) throws XmlPullParserException;
    void setInput(InputStream in, String encoding) throws XmlPullParserException;
    void setInput(Reader in) throws XmlPullParserException;
    int getEventType() throws XmlPullParserException;
    int next() throws XmlPullParserException, IOException;
    String getName();
    String getText();
    String nextText() throws XmlPullParserException, IOException;
    String getAttributeValue(String namespace, String name);
    int getAttributeCount();
    String getAttributeName(int i);
    String getAttributeValue(int i);
    int getDepth();
}
