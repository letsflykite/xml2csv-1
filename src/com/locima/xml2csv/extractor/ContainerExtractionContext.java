package com.locima.xml2csv.extractor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.sf.saxon.s9api.XPathSelector;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.locima.xml2csv.configuration.IMapping;
import com.locima.xml2csv.configuration.IMappingContainer;
import com.locima.xml2csv.configuration.XPathValue;
import com.locima.xml2csv.util.StringUtil;

/**
 * Used to manage the evaluation and storage of results of an {@link IMappingContainer} instance.
 */
public class ContainerExtractionContext extends ExtractionContext implements Iterable<List<ExtractedField>> {

	private static final Logger LOG = LoggerFactory.getLogger(ContainerExtractionContext.class);

	/**
	 * A list of all the child contexts (also a list) found as a result of evaluating the {@link ContainerExtractionContext#mapping}'s
	 * {@link IMappingContainer#getMappingRoot()} query.
	 */
	private List<List<ExtractionContext>> children;

	/**
	 * The index that this extraction context appears in relative to its siblings. Set on constructor and never changed. Used for generating field
	 * name prefixes.
	 */
	private final int index;

	/**
	 * The mapping that this extraction context is representing the evaluation of.
	 */
	private IMappingContainer mapping;

	public ContainerExtractionContext(ContainerExtractionContext parent, IMappingContainer mapping, int index) {
		super(parent);
		this.mapping = mapping;
		this.children = new ArrayList<List<ExtractionContext>>();
		this.index = index;
	}

	public ContainerExtractionContext(IMappingContainer mapping, int index) {
		this(null, mapping, index);
	}

	/**
	 * Execute this mapping for the passed XML document by:
	 * <ol>
	 * <li>Getting the mapping root(s) of the mapping, relative to the rootNode passed.</li>
	 * <li>If there isn't a mapping root, use the root node passed.</li>
	 * <li>Execute this mapping for each of the root(s).</li>
	 * <li>Each execution results in a single call to om (one CSV line).</li>
	 * </ol>
	 */
	@Override
	public void evaluate(XdmNode rootNode) throws DataExtractorException {
		XPathValue mappingRoot = this.mapping.getMappingRoot();
		// If there's no mapping root expression, use the passed node as a single root
		int valueCount = 0;
		if (mappingRoot != null) {
			LOG.debug("Executing mappingRoot {} for {}", mappingRoot, this.mapping);
			XPathSelector rootIterator = mappingRoot.evaluate(rootNode);
			for (XdmItem item : rootIterator) {
				if (item instanceof XdmNode) {
					// All evaluations have to be done in terms of nodes, so if the XPath returns something like a value then warn and move on.
					evaluateChildren((XdmNode) item);
				} else {
					LOG.warn("Expected to find only elements after executing XPath on mapping list, got {}", item.getClass().getName());
				}
				valueCount++;
			}
		} else {
			// If there is no root specified by the contextual context, then use "." , or current node passed as rootNode parameter.
			if (LOG.isDebugEnabled()) {
				LOG.debug("No mapping root specified for {}, so executing against passed context node", mappingRoot, this.mapping);
			}
			evaluateChildren(rootNode);
			valueCount = 1;
		}

		// Keep track of the most number of results we've found for a single invocation of the mapping root.
		this.mapping.setHighestFoundValueCount(valueCount);

		if (LOG.isTraceEnabled()) {
			LOG.trace("START RESULTS OUTPUT after completed mapping container {} against document", this);
			logResults(this, 0, 0);
			LOG.trace("END RESULTS OUTPUT");
		}
	}

	/**
	 * Evaluates a nested mapping, appending the results to the output line passed.
	 *
	 * @param node the node from which all mappings will be based on.
	 * @param trimWhitespace if true, then leading and trailing whitespace will be removed from all data values.
	 * @throws DataExtractorException if an error occurred whilst extracting data (typically this would be caused by bad XPath, or XPath invalid from
	 *             the <code>mappingRoot</code> specified).
	 */
	private void evaluateChildren(XdmNode node) throws DataExtractorException {
		if (LOG.isDebugEnabled()) {
			LOG.debug("Executing {} child mappings of {}", this.mapping.size(), this.mapping);
		}
		int i = 0;
		List<ExtractionContext> iterationECs = new ArrayList<ExtractionContext>(size());
		for (IMapping mapping : this.mapping) {
			ExtractionContext childCtx = ExtractionContext.create(this, mapping, i);
			childCtx.evaluate(node);
			iterationECs.add(childCtx);
			i++;
		}
		this.children.add(iterationECs);
	}

	public List<List<ExtractionContext>> getChildren() {
		return this.children;
	}

	public int getIndex() {
		return this.index;
	}

	@Override
	public IMappingContainer getMapping() {
		return this.mapping;
	}

	@Override
	public String getName() {
		return this.mapping.getContainerName();
	}

	public List<ExtractionContext> getResultsSetAt(int valueIndex) {
		if (this.children.size() > valueIndex) {
			return this.children.get(valueIndex);
		} else {
			return null;
		}
	}

	@Override
	public Iterator<List<ExtractedField>> iterator() {
		return new OutputRecordIterator(this);
	}

	/**
	 * Debugging method to log all the results when an {@link #evaluate(XdmNode)} call has completed.
	 */
	private void logResults(ExtractionContext ctx, int offset, int indentCount) {
		StringBuilder indentSb = new StringBuilder();
		for (int i = 0; i < indentCount; i++) {
			indentSb.append("  ");
		}
		String indent = indentSb.toString();
		if (ctx instanceof ContainerExtractionContext) {
			if (LOG.isTraceEnabled()) {
				LOG.trace("{}{}:{}", indent, offset, this);
			}
			int childResultsSetCount = 0;
			int childCount = 0;
			for (List<ExtractionContext> children : ((ContainerExtractionContext) ctx).getChildren()) {
				LOG.trace("{}  {}", indent, childResultsSetCount++);
				for (ExtractionContext child : children) {
					logResults(child, childCount++, indentCount + 2);
				}
				childCount = 0;
			}
		} else {
			MappingExtractionContext mCtx = (MappingExtractionContext) ctx;
			if (LOG.isTraceEnabled()) {
				LOG.trace("{}{}:{}({})", indent, offset, mCtx, StringUtil.collectionToString(mCtx.getAllValues(offset + "_"), ",", null));
			}
		}
	}

	/**
	 * Returns the number of mapping roots found for this object to evaluate against.
	 */
	@Override
	public int size() {
		return this.children.size();
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("CEC(");
		sb.append(this.mapping);
		sb.append(", ");
		sb.append(this.index);
		sb.append(")");
		return sb.toString();
	}

}
