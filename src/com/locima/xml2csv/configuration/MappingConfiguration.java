package com.locima.xml2csv.configuration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.sf.saxon.s9api.XdmNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.locima.xml2csv.ArgumentException;
import com.locima.xml2csv.ArgumentNullException;
import com.locima.xml2csv.configuration.filter.FilterContainer;
import com.locima.xml2csv.configuration.filter.IInputFilter;
import com.locima.xml2csv.extractor.DataExtractorException;
import com.locima.xml2csv.inputparser.FileParserException;
import com.locima.xml2csv.util.StringUtil;

/**
 * A hierarchy of mappings between XPath expressions and fields.<p>
 * This is the top-level configuration object created by a single configuration file. 
 */
public class MappingConfiguration implements Iterable<IMappingContainer> {

	private static final Logger LOG = LoggerFactory.getLogger(MappingConfiguration.class);

	/**
	 * The default inline behaviour (when multiple values for fields are found) for all mappings (unless overridden).
	 */
	private MultiValueBehaviour defaultMultiValueBehaviour;

	/**
	 * The default name format to apply to all child container mappings.
	 */
	private NameFormat defaultNameFormat;

	/**
	 * Contains all the input filters that have been configured for this set of mappings.
	 */
	private FilterContainer filterContainer = new FilterContainer();

	/**
	 * The list of mappings maintained by this object.
	 */
	private List<IMappingContainer> mappings = new ArrayList<IMappingContainer>();

	/**
	 * The map of XML namespace prefix to namespace URIs used by any {@link MappingList} or {@link Mapping} contained within this configuration.
	 */
	private Map<String, String> namespaceMappings = new HashMap<String, String>();

	/**
	 * Add a new input filter to the list of filters that will be executed for all files processed by this mapping configuration.
	 *
	 * @param filter the filter to add, must not be null.
	 */
	public void addInputFilter(IInputFilter filter) {
		if (filter == null) {
			throw new ArgumentNullException("filter");
		}
		LOG.debug("Adding filter {} to mapping configuration filters", filter);
		this.filterContainer.addNestedFilter(filter);
	}

	/**
	 * Adds a child set of mappings to this mappings set.
	 *
	 * @param container a set of mappings, must not be null and must have a unique {@link IMapping#getName()} value.
	 * @return the passed <code>container</code>.
	 */
	public IMappingContainer addContainer(IMappingContainer container) {
		if (container == null) {
			throw new ArgumentNullException("maps");
		}
		// Ensure that the mapping set name is unique
		String containerName = container.getName();
		if (containerName == null) {
			throw new ArgumentException("maps", "contains a null name.");
		}
		if (containsContainer(containerName)) {
			throw new ArgumentException("maps", "must contain a unique name");
		}
		this.mappings.add(container);
		return container;
	}

	/**
	 * Adds a namespace prefix to URI mapping that can be used in any descendant mapping.
	 *
	 * @param prefix The prefix that may be used within a descendant mapping. Null indicates default namespace.
	 * @param uri The URI that it maps to. Must not be null.
	 * @throws FileParserException If an attempt is made to reassign an existing prefix/URI mapping to a new URI.
	 */
	public void addNamespaceMapping(String prefix, String uri) throws FileParserException {
		if (StringUtil.isNullOrEmpty(uri)) {
			throw new ArgumentNullException("uri");
		}
		String existingUri = this.namespaceMappings.get(prefix);
		if (existingUri != null) {
			if (uri.equals(existingUri)) {
				LOG.debug("Ignoring duplicate namespace prefix declaration {} -> {}", prefix, uri);
			} else {

				throw new FileParserException(
								"Cannot tolerate the same namespace prefix used for different URIs in mapping config (%s maps to %s and %s", prefix,
								existingUri, uri);
			}
		} else {
			this.namespaceMappings.put(prefix, uri);
		}
	}

	/**
	 * Determines whether this object already contains a mapping contains with the same name (this isn't allowed).
	 *
	 * @param name the name of the mapping set to return
	 * @return null if a mapping set with that name could not be found.
	 */
	public boolean containsContainer(String name) {
		for (IMappingContainer mapping : this.mappings) {
			if (mapping.getName().equals(name)) {
				return true;
			}
		}
		return false;
	}

/**
	 * Retrieve a top level mapping container ({@link MappingList} by name, or null if it doesn't exist.
	 * @param containerName the name of the mapping container (needs to match {@link IMapping#getName()}).
	 * @return a mapping container instance with the matching name, or null if one doesn't exist.
	 */
	public IMappingContainer getContainerByName(String containerName) {
		for (IMappingContainer container : this.mappings) {
			if (container.getName().equals(containerName)) {
				return container;
			}
		}
		return null;
	}

	/**
	 * Retrieve the default multi-value behaviour that should be inherited by all child containers and value mappings.
	 *
	 * @return a default behaviour for child container and value mappings.
	 */
	public MultiValueBehaviour getDefaultMultiValueBehaviour() {
		return this.defaultMultiValueBehaviour;
	}

	/**
	 * Retrieve the namespace prefix to URI map that's associated with this configuration.
	 * <p>
	 * These are applied to all the XPath statements in mappings and mapping roots.
	 *
	 * @return a possibly empty map which mappings a namespace prefix to a URI.
	 */
	public Map<String, String> getNamespaceMap() {
		return this.namespaceMappings;
	}

	/**
	 * Returns true if this mapping configuration is interested in processing the passed XML file.
	 *
	 * @param xmlFile the XML file to test. Must not be null.
	 * @return true if the file should be processed, false otherwise.
	 */
	public boolean include(File xmlFile) {
		return this.filterContainer.include(xmlFile);
	}

	/**
	 * Returns true if this mapping configuration is interested in processing the passed XML document.
	 *
	 * @param xmlDoc the XML document to test. Must not be null.
	 * @return true if the document should be processed, false otherwise.
	 * @throws DataExtractorException if there was a problem executing the filter.
	 */
	public boolean include(XdmNode xmlDoc) throws DataExtractorException {
		return this.filterContainer.include(xmlDoc);
	}

	@Override
	public Iterator<IMappingContainer> iterator() {
		return this.mappings.iterator();
	}

	/**
	 * Recursively log this configuration.
	 *
	 * @see MappingConfiguration#log(IMapping, int)
	 */
	public void log() {
		if (LOG.isDebugEnabled()) {
			for (IMappingContainer mappingList : this.mappings) {
				LOG.debug(mappingList.toString());
				for (IMapping child : mappingList) {
					log(child, 1);
				}
			}
		}
	}

	/**
	 * Recursive worked method for {@link MappingConfiguration#log()}.
	 *
	 * @param mapping the mapping to log information for.
	 * @param indentCount th eamount to indent each line by, to show hierarchy.
	 */
	private void log(IMapping mapping, int indentCount) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < indentCount; i++) {
			sb.append('\t');
		}
		sb.append(mapping.toString());
		LOG.debug(sb.toString());
		if (mapping instanceof IMappingContainer) {
			for (IMapping child : (IMappingContainer) mapping) {
				if (child instanceof IMappingContainer) {
					log(child, 1);
				} else {
					log(child, 1);
				}
			}
		}
	}

	/**
	 * Sets the default inline behaviour for all child mappings of this configuration.
	 *
	 * @param defaultMultiValueBehaviour the default inline behaviour for child mappings.
	 */
	public void setDefaultMultiValueBehaviour(MultiValueBehaviour defaultMultiValueBehaviour) {
		this.defaultMultiValueBehaviour =
						(defaultMultiValueBehaviour == MultiValueBehaviour.DEFAULT) ? MultiValueBehaviour.LAZY : defaultMultiValueBehaviour;
	}

	/**
	 * Sets the default name format for all child value mappings of this configuration.
	 *
	 * @param format the format to use.
	 */
	public void setDefaultNameFormat(NameFormat format) {
		this.defaultNameFormat = format;
	}

	/**
	 * Gets the default name format for all child value mappings of this configuration.
	 *
	 * @return format the format to use.  May be null.
	 */
	public NameFormat setDefaultNameFormat() {
		return this.defaultNameFormat;
	}

	/**
	 * Returns the number of mappings contained in the configuration.
	 *
	 * @return a natural number.
	 */
	public int size() {
		return this.mappings.size();
	}

}
