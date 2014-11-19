package com.locima.xml2csv.output;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.locima.xml2csv.BugException;
import com.locima.xml2csv.model.IMapping;
import com.locima.xml2csv.model.IMappingContainer;
import com.locima.xml2csv.model.MappingConfiguration;
import com.locima.xml2csv.model.MultiValueBehaviour;
import com.locima.xml2csv.model.RecordSet;

/**
 * Used to create {@link IOutputManager} instances, using a concrete implementation that is suitable for the mapping configuration passed.
 */
public class OutputManager implements IOutputManager {

	private static final Logger LOG = LoggerFactory.getLogger(OutputManager.class);

	/**
	 * The directory that all outputs will be written to.
	 */
	private File directory;

	/**
	 * Maps output name to the appropriate writer.
	 */
	private Map<String, ICsvWriter> outputToWriter;

	public OutputManager() {
	}

	/**
	 * Finalises all the output writers managed by this instance.
	 *
	 * @throws OutputManagerException if an error occurs whilst closing an output file.
	 */
	@Override
	public void close() throws OutputManagerException {
		LOG.info("Closing {} ICsvWriters", this.outputToWriter.size());
		for (Entry<String, ICsvWriter> entry : this.outputToWriter.entrySet()) {
			LOG.info("Closing ICsvWriter {} ({})", entry.getKey(), entry.getValue());
			entry.getValue().close();
		}
	}

	/**
	 * This is the logic to determine which {@link IOutputManager} is appropriate.
	 *
	 * @param container the mapping container to search for unbounded inline mappings within.
	 * @return true if an bounded inline mapping was found, false otherwise.
	 */
	private boolean includesUnboundedInline(IMappingContainer container) {
		LOG.debug("Checking {} for inline multi-value behaviour", container);
		for (IMapping mapping : container) {
			MultiValueBehaviour mvb = mapping.getMultiValueBehaviour();
			if ((mvb == MultiValueBehaviour.INLINE) || (mvb == MultiValueBehaviour.WARN)) {
				LOG.debug("MappingConfiguraton contains a inline configuration for {}, therefore returning true", mapping);
				return true;
			}
			if (mapping instanceof IMappingContainer) {
				return includesUnboundedInline((IMappingContainer) mapping);
			}
		}
		return false;
	}

	/**
	 * Creates an appropriate {@link IOutputManager} based on the mapping configuration provided. The decision logic for which implementation to use
	 * is based on whether a mapping configuration contains any unbounded inline mappings. These produce a variable number of field values in any
	 * given record. If one is found then it means that we can't directly stream out a CSV file using {@link DirectCsvWriter} (because we wouldn't
	 * know how many fields to include in any recrd), so we have to use {@link InlineCsvWriter} instead.
	 *
	 * @param config the mapping configuration that we are going to output the results of.
	 */
	@Override
	public void initialise(File outputDirectory, MappingConfiguration config, boolean appendOutput) throws OutputManagerException {
		setDirectory(outputDirectory);

		this.outputToWriter = new HashMap<String, ICsvWriter>();
		for (IMappingContainer mappingContainer : config) {
			String outputName = mappingContainer.getContainerName();
			ICsvWriter writer;
			if (includesUnboundedInline(mappingContainer)) {
				LOG.info("Unbounded inline detected for {}, therefore creating InlineCsvWriter", outputName);
				writer = new InlineCsvWriter();
			} else {
				LOG.info("No unbounded mappings detected for {}, therefore using the DirectCsvWriter", outputName);
				writer = new DirectCsvWriter();
			}
			writer.initialise(outputDirectory, mappingContainer, appendOutput);
			this.outputToWriter.put(outputName, writer);
		}
	}

	/**
	 * Sets the directory to which output files will be written.
	 *
	 * @param directory the output directory.
	 * @throws OutputManagerException If the directory does not exist
	 */
	private void setDirectory(File directory) throws OutputManagerException {
		this.directory = directory;
		if (!directory.isDirectory()) {
			throw new OutputManagerException("Output directory specified is not a directory: %1$s", directory.getAbsolutePath());
		}
		if (!directory.canWrite()) {
			throw new OutputManagerException("Output directory is not writeable: %1$s", directory.getAbsolutePath());
		}
		LOG.info("Configured output directory as {}", this.directory.getAbsolutePath());
	}

	/**
	 * Writes the records created by the XML data extractor to the appropraite output writer managed by this instance.
	 *
	 * @param records the records to write out.
	 * @throws OutputManagerException if an unrecoverable error occurs whilst writing to the output file.
	 */
	@Override
	public void writeRecords(String outputName, RecordSet records) throws OutputManagerException {
		ICsvWriter writer = this.outputToWriter.get(outputName);
		if (writer != null) {
			writer.writeRecords(records);
		} else {
			throw new BugException("writeRecords was asked to write records for a non-existant writer: %s", outputName);
		}
	}

}