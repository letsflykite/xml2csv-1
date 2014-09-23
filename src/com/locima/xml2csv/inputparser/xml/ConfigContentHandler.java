package com.locima.xml2csv.inputparser.xml;

import java.util.Stack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import com.locima.xml2csv.XMLException;
import com.locima.xml2csv.inputparser.MappingsSet;
import com.locima.xml2csv.inputparser.NameToXPathMappings;

/**
 * The SAX Content Handler for input XML files.
 */
public class ConfigContentHandler extends DefaultHandler {

	private static final Logger LOG = LoggerFactory.getLogger(ConfigContentHandler.class);

	private String defaultSchemaNamespace;

	private Locator documentLocator;

	private MappingsSet mappingSet;

	private Stack<NameToXPathMappings> mappingStack;

	/**
	 * Adds a column mapping to the current NameToXPathMappings instance being defined.
	 *
	 * @param name the name of the column
	 * @param xPath the XPath that should be executed to get the value of the column.
	 * @throws SAXException if an error occurs while parsing the XPath expression found (will wrap {@link XMLException}.
	 */
	private void addField(String name, String xPath) throws SAXException {
		NameToXPathMappings current = this.mappingStack.peek();
		try {
			current.put(name, this.defaultSchemaNamespace, xPath);
		} catch (XMLException e) {
			throw getException(e, "Unable to add field");
		}
	}

	@Override
	public void endDocument() throws SAXException {
		if (!this.mappingStack.empty()) {
			throw getException(null, "Mapping stack should be empty, contains %s elements!", this.mappingStack.size());
		}
	}

	@Override
	public void endElement(String uri, String localName, String qName) throws SAXException {
		if (LOG.isTraceEnabled()) {
			LOG.trace("endElement(URI={})(localName={})(qName={})", uri, localName, qName);
		}
		if ("Mapping".equals(qName)) {
			NameToXPathMappings current = this.mappingStack.pop();
			this.mappingSet.addMappings(current);
		}
	}

	/**
	 * Returns the string value specified for an xsd boolean type as a Java boolean.
	 *
	 * @param value the value found in an XML attribute.
	 * @return <code>true</code> if the values <code>true</code> or <code>1</code> are passed, false otherwise.
	 */
	private boolean getBoolean(String value) {
		return ("true".equals(value) || "1".equals(value));
	}

	/**
	 * Creates an exception to be thrown by this content handler, ensuring that formatting is consistent and including locator information.
	 *
	 * @param inner the exception that caused this exception to be created. May be null.
	 * @param message the message formatting string.
	 * @param parameters parameters to the message formatting string.
	 * @return an exception, ready to be thrown.
	 */
	private SAXException getException(Exception inner, String message, Object... parameters) {
		String temp = String.format(message, parameters);
		XMLException de =
						new XMLException(inner, "Error parsing %s(%d,%d) %s", this.documentLocator.getSystemId(),
										this.documentLocator.getLineNumber(), this.documentLocator.getColumnNumber(), temp);
		SAXException se = new SAXException(de);
		return se;
	}

	/**
	 * Get all the mappings that have been found so far by this parser.
	 *
	 * @return a set of mappings, possibly empty and possible null if no files have been parsed.
	 */
	public MappingsSet getMappings() {
		return this.mappingSet;
	}

	@Override
	public void setDocumentLocator(Locator locator) {
		this.documentLocator = locator;
	}

	/**
	 * Initialises the parser for a new mappings set.
	 *
	 * @param schemaNamespace the namespace for the schema.
	 */
	private void setMappingSet(String schemaNamespace) {
		this.mappingSet = new MappingsSet();
		this.mappingStack = new Stack<NameToXPathMappings>();
		this.defaultSchemaNamespace = schemaNamespace;
	}

	/**
	 * Delegates to various helper methods that manage the opening tag of the following elements: MappingSet, Mapping or Field.
	 *
	 * @param uri the Namespace URI, or the empty string if the element has no Namespace URI or if Namespace processing is not being performed.
	 * @param localName the local name (without prefix), or the empty string if Namespace processing is not being performed.
	 * @param qName the qualified name (with prefix), or the empty string if qualified names are not available.
	 * @param atts the attributes attached to the element. If there are no attributes, it shall be an empty Attributes object. The value of this
	 *            object after startElement returns is undefined.
	 * @throws SAXException if any errors occur, usually caused by bad XPath expressions defined in the mapping input configuration. Typically wraps
	 *             {@link XMLException}
	 */
	@Override
	public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
		if (LOG.isTraceEnabled()) {
			LOG.trace("startElement(URI={})(localName={})(qName={})", uri, localName, qName);
			for (int i = 0; i < atts.getLength(); i++) {
				LOG.trace("Attr[{}](LocalName={})(QName={})(Type={})(URI={})(Value={})", i, atts.getLocalName(i), atts.getQName(i), atts.getType(i),
								atts.getURI(i), atts.getValue(i));
			}
		}

		if ("Field".equals(qName)) {
			addField(atts.getValue("name"), atts.getValue("xPath"));
		} else if ("Mapping".equals(qName)) {
			startMapping(atts.getValue("mappingRoot"), atts.getValue("outputName"), getBoolean(atts.getValue("inline")));
		} else if ("MappingSet".equals(qName)) {
			setMappingSet(atts.getValue("inputSchema"));
		} else {
			LOG.warn("Ignoring element as I wasn't expecting it or wasn't using it.");
		}
	}

	/**
	 * Initialises a new NameToXPathMappings object based on a Mapping element.
	 *
	 * @param mappingRoot The XPath expression that identifies the "root" elements for the mapping.
	 * @param outputName The name of the output that this set of mappings should be written to.
	 * @param inline If set to true then output will be flushed inline with the parent. If true and there is no parent {@link NameToXPathMappings}
	 *            then an {@link DataExtractorException} is thrown (wrapped in a {@link SAXException}).
	 * @throws SAXException If any problems occur with the XPath in the mappingRoot attribute.
	 */
	private void startMapping(String mappingRoot, String outputName, boolean inline) throws SAXException {
		NameToXPathMappings newMapping = new NameToXPathMappings();
		if ((this.mappingStack.size() == 0) && inline) {
			throw getException(null, "Cannot mark top level Mapping elements as inline");
		}

		try {
			newMapping.setMappingRoot(this.defaultSchemaNamespace, mappingRoot);
			newMapping.setInline(inline);
		} catch (XMLException e) {
			throw getException(e, "Invalid XPath found in mapping root");
		}
		newMapping.setName(outputName);
		this.mappingStack.push(newMapping);
	}

}