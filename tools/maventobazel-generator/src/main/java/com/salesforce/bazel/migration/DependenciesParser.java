/* 
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license. 
 * For full license text, see LICENSE.txt file in the repo root  or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.bazel.migration;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses raw text files, looking for dependency line items. 
 * This class specifically supports two input formats, but we try to tolerate anything that has the information.
 * <p>
 * Specifically, these formats will parse correctly:<ul>
 * <li><i>mvn dependency:list</i> output: <b>[INFO]    org.mockito:mockito-core:jar:1.10.19:test</b>
 * <li>WORKSPACE .bzl file from running Bazel's <i>migration-tooling</i>: <b>artifact = "org.slf4j:slf4j-api:1.6.2",</b>
 * <li>WORKSPACE (e.g. as generated by this tool): <b>artifact = "ch.qos.logback:logback-classic:1.1.11",</b>
 * </ul>
 * <p>
 * <b>Usage Notes:</b>
 * <ul>
 * <li>The single input file can contain lines of all the supported formats. Meaning you can concatenate the output 
 * of the <i>mvn dependency:list</i> command to the end of a WORKSPACE file, and all the dependencies will be correctly parsed</li>
 * <li>Any line that does not match the above formats is ignored.</li> 
 * <li>The parser expects a single line to contain at most one dependency</li>
 * </ul>
 *
 * @author plaird
 */
public class DependenciesParser {
	public MavenDependencyArbiter arbiter;
	public int ignoredLineCount = 0;
	public int parseErrorLineCount = 0;

	public DependenciesParser(MavenDependencyArbiter arbiter) {
		this.arbiter = arbiter;
	}
	
    /**
     * Parses a file, and returns a list of dependency POJOs 
     * 
     * @param file text file, see this class Javadoc for expected formats
     * @return a list of zero or more MavenDependency objects parsed from the file 
     */
    public List<MavenDependency> parseFile(File file) throws Exception {
        List<String> rawLines = readFileLines(file);
        return parseFileLines(rawLines);
    }

    /**
     * Parses a list of text lines, and returns a list of dependency POJOs 
     * 
     * @param rawLines raw lines of text, see this class Javadoc for expected formats
     * @return a list of zero or more MavenDependency objects parsed from the lines 
     */
    public List<MavenDependency> parseFileLines(List<String> rawLines) throws Exception {
        List<MavenDependency> dependencies = new ArrayList<>();
        
        for (String rawLine : rawLines) {
            try {
                MavenDependency dep = parseDependencyLine(rawLine);
                if (dep != null) {
                    dependencies.add(dep);
                }
            } catch (Exception anyE) {
                System.err.println(">>> FAILURE parsing line: "+rawLine);
                throw anyE;
            }
        }
        return dependencies;
    }
    
    /**
     * Parses a candidate dependency line, and returns a dependency POJO if there appears to be dependency info in the line.
     * See the class Javadoc for supported dependency line formats.
     * 
     * @param rawLine a line of text, see this class Javadoc for expected formats
     * @return a MavenDependency object, or null if the text line does not appear to express a dependency
     */
    public MavenDependency parseDependencyLine(final String rawLine) {
        boolean isMavenDependencyFormat = true;

        String parsedLine = rawLine.trim();
        if (parsedLine.startsWith("# RULE")) {
            // this is an arbiter rule
        	if (arbiter != null) {
        		MavenDependencyArbiterRule rule = arbiter.addArbiterRule(parsedLine.substring(6));
        		System.out.println(" ADDED RULE: "+rule);
        	} else {
        		System.out.println(" WARNING found an arbiter rule but no arbiter is configured. "+parsedLine);
        	}
            return null;
        }
        if (parsedLine.contains("Finished at") || parsedLine.contains("Download") || parsedLine.startsWith("[INFO] ---")) {
            // mvn dependency:list has some doppleganger log lines that confuse our simple parsing scheme due to
            // the number of colons they contain, just defeat them here.
    		//System.out.println(" IGNORE "+parsedLine);
        	ignoredLineCount++;
            return null;
        }
        if (parsedLine.startsWith("#")) {
            // commented out line
    		//System.out.println(" IGNORE "+parsedLine);
        	ignoredLineCount++;
            return null;
        }
        
        // mvn dependency:list format: remove '[INFO]'
        //    [INFO]    org.mockito:mockito-core:jar:1.10.19:test  =>  org.mockito:mockito-core:jar:1.10.19:test
        if (parsedLine.startsWith("[INFO]")) {
            parsedLine = parsedLine.substring(6);
        }

        // WORKSPACE format: remove quotes, trailing comma, and leading 'artifact ='
        //    artifact = "org.slf4j:slf4j-api:1.6.2",  =>   org.slf4j:slf4j-api:1.6.2
        parsedLine = parsedLine.replace("\"", "");
        parsedLine = parsedLine.replace(",", "");
        if (parsedLine.startsWith("artifact =")) {
        	isMavenDependencyFormat = false;
            parsedLine = parsedLine.substring(10);
        }

        // now see if we have a dependency entry, ignore this line if we dont
        parsedLine = parsedLine.trim();
        if (parsedLine.length() == 0) {
        	// just a blank line
    		ignoredLineCount++;
            return null;
        }
        String[] parts = parsedLine.split(":");
        if (parts.length < 3) {
            // not a dependency line; e.g. a comment line, or something else in WORKSPACE file
    		//System.out.println(" IGNORE "+parsedLine);
    		ignoredLineCount++;
            return null;
        }

        // assume there are 3 parts group:artifact:version, and then expand from there if there are more
        // org.slf4j:slf4j-api:1.6.2   =>  [org.slf4j] [slf4j-api] [1.6.2]
        String group = parts[0];
        String artifact = parts[1];
        String scope = "compile";
        String version = parts[2];
        String classifier = null;

        if (parts.length == 5) {
        	if (isMavenDependencyFormat) {
        		// mvn dependency:list format with type and scope
            	//   group:artifact:TYPE:version:SCOPE
	        	//   org.sample:foo:jar:1.2.3:compile  =>  [org.sample] [foo] [jar] [1.2.3] [compile]  
	            scope = parts[4];
	            version = parts[3];
	            classifier = null;
        	} else {
        		// Bazel WORKSPACE format, with classifier
            	//   group:artifact:TYPE:CLASSIFIER:version
        		//   artifact = "io.netty:netty-transport-native-epoll:jar:linux-x86_64:4.1.8.Final",
	            version = parts[4];
	            classifier = parts[3];
        	}
        } else if (parts.length == 6) {
    		// mvn dependency:list format with type and scope and classifier
        	//   group:artifact:TYPE:CLASSIFIER:version:SCOPE
            //   com.foo.bar:somelib:jar:idl:2.0.0:compile  => [com.foo.bar] [somelib] [jar] [idl] [2.0.0] [compile]
            scope = parts[5];
            version = parts[4];
            classifier = parts[3];
        }
        
        MavenDependency dep = null;
        try {
            dep = new MavenDependency(rawLine, group, artifact, scope, version, classifier);
            System.out.println(" ADDED DEP "+dep);
        } catch (Exception anyE) {
    		ignoredLineCount++;
        	parseErrorLineCount++;
            System.out.println(" PLEASE CHECK This line has colons in it, but I don't think it is a dependency. Ignoring. Line: "+rawLine);
        }
        return dep;
    }
    
    private List<String> readFileLines(File file) throws Exception {
        List<String> lines = new ArrayList<>();
        String line = null;

        FileReader fileReader = new FileReader(file);
        BufferedReader bufferedReader = new BufferedReader(fileReader);
        while ((line = bufferedReader.readLine()) != null) {
            lines.add(line);
        }
        bufferedReader.close();
        return lines;
    }

}
