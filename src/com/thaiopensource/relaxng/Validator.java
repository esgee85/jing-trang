package com.thaiopensource.relaxng;

import org.xml.sax.SAXParseException;
import org.xml.sax.SAXException;
import org.xml.sax.Locator;
import org.xml.sax.XMLReader;
import org.xml.sax.ErrorHandler;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.helpers.LocatorImpl;

import java.util.Hashtable;
import java.util.Enumeration;
import java.util.Vector;

import com.thaiopensource.datatype.DatatypeContext;

public class Validator implements KeyChecker {
  PatternBuilder b;
  Locator locator;
  private XMLReader xr;
  private boolean hadError = false;
  boolean keyAmbig;

  static final int RECOVERY_ATTEMPTS = 2;
  PrefixMapping prefixMapping = new PrefixMapping("xml", PatternReader.xmlURI, null);

  static final class PrefixMapping implements DatatypeContext {
    private final String prefix;
    private final String namespaceURI;
    private final PrefixMapping prev;
    
    PrefixMapping(String prefix, String namespaceURI, PrefixMapping prev) {
      this.prefix = prefix;
      this.namespaceURI = namespaceURI;
      this.prev = prev;
    }

    PrefixMapping getPrevious() {
      return prev;
    }

    public String getNamespaceURI(String prefix) {
      PrefixMapping tem = this;
      do { 
	if (tem.prefix.equals(prefix))
	  return tem.namespaceURI;
	tem = tem.prev;
      } while (tem != null);
      return null;
    }
  }

  class Handler implements ContentHandler {
    Pattern combinedState;
    Handler parent;
    boolean collectingCharacters = false;
    StringBuffer charBuf;

    Handler(Pattern pattern) {
      combinedState = pattern;
      parent = null;
    }
    
    void startCollectingCharacters() {
      if (!collectingCharacters) {
	collectingCharacters = true;
	charBuf = new StringBuffer();
      }
    }

    Handler(Handler parent, Pattern combinedState) {
      this.parent = parent;
      this.combinedState = combinedState;
    }

    void set() {
      xr.setContentHandler(this);
    }

    void flushCharacters() throws SAXException {
      collectingCharacters = false;
      int len = charBuf.length();
      for (int i = 0; i < len; i++) {
	switch (charBuf.charAt(i)) {
	case ' ':
	case '\r':
	case '\t':
	case '\n':
	  break;
	default:
	  string(new StringAtom(charBuf.toString(), prefixMapping));
	  return;
	}
      }
    }

    public void startElement(String namespaceURI,
			     String localName,
			     String qName,
			     Attributes atts) throws SAXException {
      if (collectingCharacters)
	flushCharacters();
      PatternSet ts = new PatternSet();
      initialContentPatterns(namespaceURI, localName, ts);
      Pattern[] childPatterns = ts.toArray();
      Pattern childCombinedState
	= combinedState.combinedInitialContentPattern(b,
						   namespaceURI,
						   localName,
						   0);
      if (childCombinedState.isEmptyChoice()
	  && !combinedState.isEmptyChoice()) {
	error("impossible_element", localName);
	for (int level = 1; level <= RECOVERY_ATTEMPTS; level++) {
	  childCombinedState 
	    = combinedState.combinedInitialContentPattern(b,
						       namespaceURI,
						       localName,
						       level);
	  if (!childCombinedState.isEmptyChoice())
	    break;
	}
      }
      Handler h;
      if (childPatterns.length <= 1)
      	h = new UnambigHandler(this, childCombinedState, null);
      else
	h = new AmbigHandler(this, childCombinedState, childPatterns);
      h.set();
      h.attributes(atts);
    }

    void initialContentPatterns(String namespaceURI, String localName,
			     PatternSet ts) {
      combinedState.initialContentPatterns(namespaceURI, localName, ts);
    }

    public void endElement(String namespaceURI,
			   String localName,
			   String qName) throws SAXException {
      if (collectingCharacters)
	flushCharacters();
      if (!combinedState.isNullable() && !combinedState.isEmptyChoice())
	error("unfinished_element");
      parent.set();
    }

    void attributes(Attributes atts) throws SAXException {
      int len = atts.getLength();
      for (int i = 0; i < len; i++) {
	AttributeAtom a = new AttributeAtom(atts.getURI(i),
					    atts.getLocalName(i),
					    atts.getValue(i),
					    prefixMapping);
	if (!updateState(b.memoizedResidual(combinedState, a))) {
	  // null says allow any value
	  a = new AttributeAtom(atts.getURI(i),
				atts.getLocalName(i),
				null,
				null);
	  // XXX specify namespace in error message?
	  if (updateState(b.memoizedResidual(combinedState, a)))
	    error("bad_attribute_value", atts.getLocalName(i));
	  else
	    error("impossible_attribute_ignored", atts.getLocalName(i));
	}
	else
	  a.checkKeys(Validator.this);
      }
      if (!updateState(b.memoizedEndAttributes(combinedState, false))) {
	// XXX should specify which attributes
	error("required_attributes_missing");
	combinedState = b.memoizedEndAttributes(combinedState, true);
      }
      if (combinedState.getContentType() == Pattern.DATA_CONTENT_TYPE)
	startCollectingCharacters();
    }

    void setState(Pattern nextState) {
      this.combinedState = nextState;
    }

    void setState(String childNamespaceURI, String childLocalName) {
      Atom a = new AnyContentElementAtom(childNamespaceURI, childLocalName);
      endChild(a);
      if (!updateState(b.memoizedResidual(combinedState, a))) {
	// XXX recover by trying to construct pattern that represents
	// possibilities after an element
      }
    }

    void setState(Pattern[] childPatterns,
		  Pattern[] childState,
		  String childNamespaceURI,
		  String childLocalName) {
      for (int i = 0; i < childState.length; i++) {
	if (childState[i].isNullable())
	  childState[i] = childPatterns[i];
	else
	  childState[i] = null;
      }
      Atom a = new ElementAtom(childNamespaceURI, childLocalName, childState);
      endChild(a);
      if (!updateState(b.memoizedResidual(combinedState, a))) {
	if (!hadError)
	  throw new Error("cannot happen: internal validation error");
	// Recover by assuming all possible childPatterns matched
	a = new ElementAtom(childNamespaceURI, childLocalName, childPatterns);
	if (!updateState(b.memoizedResidual(combinedState, a))) {
	  a = new AnyContentElementAtom(childNamespaceURI, childLocalName);
	  if (!updateState(b.memoizedResidual(combinedState, a))) {
	    // XXX recover by trying to construct pattern that represents
	    // possibilities after an element
	  }
	}
      }
    }

    void endChild(Atom a) {
    }

    void text() throws SAXException {
      if (!updateState(b.memoizedTextResidual(combinedState)))
	error("text_not_allowed");
    }

    void string(StringAtom a) throws SAXException {
      if (!updateState(b.memoizedResidual(combinedState, a)))
	error("string_not_allowed");
      else
	a.checkKeys(Validator.this);
    }

    public void characters(char ch[], int start, int length) throws SAXException {
      if (collectingCharacters) {
	charBuf.append(ch, start, length);
	return;
      }
      for (int i = 0; i < length; i++) {
	switch (ch[start + i]) {
	case ' ':
	case '\r':
	case '\t':
	case '\n':
	  break;
	default:
	  {
	    text();
	    return;
	  }
	}
      }
    }

    // Return true if we need to give an error message.

    private boolean updateState(Pattern nextCombinedState) {
      if (nextCombinedState.isEmptyChoice())
	return combinedState.isEmptyChoice();
      else {
	combinedState = nextCombinedState;
	return true;
      }
    }

    public void endDocument() throws SAXException {
      if (parent != null)
	hadError = true;
      if (!combinedState.isNullable())
	error("document_incomplete");
      if (!hadError)
	checkKeyComplete();
    }

    public void setDocumentLocator(Locator loc) {
      locator = loc;
    }

    public void startDocument() { }
    public void processingInstruction(String target, String date) { }
    public void skippedEntity(String name) { }
    public void ignorableWhitespace(char[] ch, int start, int len) { }
    public void startPrefixMapping(String prefix, String uri) {
      prefixMapping = new PrefixMapping(prefix, uri, prefixMapping);
    }
    public void endPrefixMapping(String prefix) {
      prefixMapping = prefixMapping.getPrevious();
    }
  }

  class UnambigHandler extends Handler {

    Pattern nextState;

    UnambigHandler(Pattern combinedState) {
      super(combinedState);
    }

    UnambigHandler(Handler parent, Pattern combinedState, Pattern nextState) {
      super(parent, combinedState);
      this.nextState = nextState;
    }

    public void startElement(String namespaceURI,
			     String localName,
			     String qName,
			     Attributes atts) throws SAXException {
      PatternPair tp = b.memoizedUnambigContentPattern(combinedState,
						 namespaceURI,
						 localName);
      if (tp == null || tp.isEmpty())
	super.startElement(namespaceURI, localName, qName, atts);
      else {
	Handler h = new UnambigHandler(this,
				       tp.getContentPattern(),
				       tp.getResidualPattern());
	h.set();
	h.attributes(atts);
      }
    }

    public void endElement(String namespaceURI,
			   String localName,
			   String qName) throws SAXException {
      super.endElement(namespaceURI, localName, qName);
      if (nextState != null)
	parent.setState(nextState);
      else
	parent.setState(namespaceURI, localName);
    }
  }

      
  class AmbigHandler extends Handler {
    private Pattern[] state;
    private Pattern[] initState;

    AmbigHandler(Handler parent, Pattern combinedState, Pattern[] initState) {
      super(parent, combinedState);
      this.initState = initState;
      state = new Pattern[initState.length];
      for (int i = 0; i < state.length; i++)
	state[i] = initState[i];
    }

    void endChild(Atom a) {
      for (int i = 0; i < state.length; i++)
	state[i] = b.memoizedResidual(state[i], a);
    }

    void initialContentPatterns(String namespaceURI, String localName,
			     PatternSet ts) {
      for (int i = 0; i < state.length; i++)
	state[i].initialContentPatterns(namespaceURI, localName, ts);
      super.initialContentPatterns(namespaceURI, localName, ts);
    }

    void text() throws SAXException {
      super.text();
      for (int j = 0; j < state.length; j++)
	state[j] = b.memoizedTextResidual(state[j]);
    }

    void string(StringAtom a) throws SAXException {
      super.string(a);
      for (int j = 0; j < state.length; j++)
	state[j] = b.memoizedResidual(state[j], a);
    }

    void attributes(Attributes atts) throws SAXException {
      super.attributes(atts);
      int len = atts.getLength();
      for (int i = 0; i < len; i++) {
	Atom a = new AttributeAtom(atts.getURI(i),
				   atts.getLocalName(i),
				   atts.getValue(i),
				   prefixMapping);
	for (int j = 0; j < state.length; j++)
	  state[j] = b.memoizedResidual(state[j], a);
      }
      for (int i = 0; i < state.length; i++) {
	state[i] = b.memoizedEndAttributes(state[i], false);
	if (state[i].getContentType() == Pattern.DATA_CONTENT_TYPE)
	  startCollectingCharacters();
      }
    }

    public void endElement(String namespaceURI,
			   String localName,
			   String qName) throws SAXException {
      super.endElement(namespaceURI, localName, qName);
      parent.setState(initState, state, namespaceURI, localName);
    }
  }

  public Validator(Pattern pattern, PatternBuilder b, XMLReader xr,
		   boolean keyAmbig) {
    this.b = b;
    this.xr = xr;
    this.keyAmbig = keyAmbig;
    new UnambigHandler(pattern).set();
  }

  public boolean getValid() {
    return !hadError;
  }

  void error(String key) throws SAXException {
    hadError = true;
    ErrorHandler eh = xr.getErrorHandler();
    if (eh != null)
      eh.error(new SAXParseException(Localizer.message(key), locator));
  }

  void error(String key, String arg) throws SAXException {
    hadError = true;
    ErrorHandler eh = xr.getErrorHandler();
    if (eh != null)
      eh.error(new SAXParseException(Localizer.message(key, arg), locator));
  }

  void error(String key, String arg1, String arg2, Locator loc) throws SAXException {
    hadError = true;
    ErrorHandler eh = xr.getErrorHandler();
    if (eh != null)
      eh.error(new SAXParseException(Localizer.message(key, arg1, arg2),
				     loc));
  }

  void error(String key, String arg1, String arg2) throws SAXException {
    hadError = true;
    ErrorHandler eh = xr.getErrorHandler();
    if (eh != null)
      eh.error(new SAXParseException(Localizer.message(key, arg1, arg2),
				     locator));
  }

  Hashtable keyTables = new Hashtable();

  static class Key {
    boolean defined;
    Locator defLoc;
    Vector refLocs;
  }

  private Key lookupKey(String name, Object value) {
    Hashtable keyTable = (Hashtable)keyTables.get(name);
    if (keyTable == null) {
      keyTable = new Hashtable();
      keyTables.put(name, keyTable);
    }
    Key key = (Key)keyTable.get(value);
    if (key == null) {
      key = new Key();
      keyTable.put(value, key);
    }
    return key;
  }

  void checkKeyComplete() throws SAXException {
    for (Enumeration e = keyTables.keys(); e.hasMoreElements();) {
      String keyName = (String)e.nextElement();
      Hashtable keyTable = (Hashtable)keyTables.get(keyName);
      for (Enumeration f = keyTable.keys(); f.hasMoreElements();) {
	Object value = f.nextElement();
	Key k = (Key)keyTable.get(value);
	if (!k.defined) {
	  for (int i = 0; i < k.refLocs.size(); i++)
	    error("undefined_keyref",
		  keyName,
		  value.toString(),
		  (Locator)k.refLocs.elementAt(i));
	}
      }
    }
  }

  public void checkKey(String name, Object value) throws SAXException {
    if (keyAmbig)
      return;
    Key k = lookupKey(name, value);
    if (k.defined)
      error("duplicate_key", name, value.toString());
    else {
      k.defined = true;
      k.defLoc = saveLocator();
    }
    k.refLocs = null;
  }

  public void checkKeyRef(String name, Object value) {
    if (keyAmbig)
      return;
    Key k = lookupKey(name, value);
    if (k.defined)
      return;
    if (k.refLocs == null)
      k.refLocs = new Vector();
    k.refLocs.addElement(saveLocator());
  }

  private Locator saveLocator() {
    if (locator == null)
      return new LocatorImpl();
    return new LocatorImpl(locator);
  }

}
