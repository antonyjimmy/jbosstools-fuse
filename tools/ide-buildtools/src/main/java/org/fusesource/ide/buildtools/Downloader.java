/*******************************************************************************
 * Copyright (c) 2013 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package org.fusesource.ide.buildtools;

import io.fabric8.insight.maven.aether.Aether;
import io.fabric8.insight.maven.aether.AetherResult;
import io.fabric8.insight.maven.aether.Repository;
import io.hawt.maven.indexer.ArtifactDTO;
import io.hawt.maven.indexer.MavenIndexerFacade;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import org.apache.camel.catalog.CamelCatalog;
import org.apache.camel.catalog.DefaultCamelCatalog;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.fusesource.ide.buildtools.Downloader.ComponentModel.ComponentParam;
import org.fusesource.ide.buildtools.Downloader.ComponentModel.UriParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

public class Downloader {

    public static Logger LOG = LoggerFactory.getLogger(Downloader.class);

    private MavenIndexerFacade indexer;
    private Aether aether;
    private File archetypeDir = new File("fuse-ide-archetypes");
    private File camelComponentMetaData = new File("camel-metadata");
    private File xsdDir = new File("fuse-ide-xsds");
    private boolean delete = true;
    
    private static final String langFilePrefix = "/*******************************************************************************\n" + 
            " * Copyright (c) 2013 Red Hat, Inc.\n" + 
            " * Distributed under license by Red Hat, Inc. All rights reserved.\n" + 
            " * This program is made available under the terms of the\n" + 
            " * Eclipse Public License v1.0 which accompanies this distribution,\n" + 
            " * and is available at http://www.eclipse.org/legal/epl-v10.html\n" + 
            " *\n" + 
            " * Contributors:\n" + 
            " *     Red Hat, Inc. - initial API and implementation\n" + 
            " ******************************************************************************/\n" + 
            "\n" + 
            "package org.fusesource.ide.camel.editor;\n" + 
            "\n" + 
            "import org.eclipse.osgi.util.NLS;\n" + 
            "\n" + 
            "/**\n" + 
            " * NOTE - this file is auto-generated.\n" +
            " *\n" +
            " * DO NOT EDIT!\n" + 
            " *\n" + 
            " * @author lhein\n" + 
            " */\n" + 
            "public class ConnectorsMessages extends NLS {\n" + 
            "\n" + 
            "    private static final String BUNDLE_NAME = \"org.fusesource.ide.camel.editor.l10n.connectorsMessages\";\n\n";
    
    private static final String langFilePostfix = "\n\n" + 
            "    static {\n" + 
            "        // initialize resource bundle\n" + 
            "        NLS.initializeMessages(BUNDLE_NAME, ConnectorsMessages.class);\n" + 
            "    }\n" + 
            "}\n";

    // setup an ignore list for unwanted archetypes
    private static ArrayList<String> ignoredArtifacts = new ArrayList<String>();

    static {
        ignoredArtifacts.add("camel-archetype-component-scala");
        ignoredArtifacts.add("camel-archetype-scala");
        ignoredArtifacts.add("camel-web-osgi-archetype");
        ignoredArtifacts.add("camel-archetype-groovy");
    }

    public static void main(String[] args) {
        try {
            // lets find the eclipse plugins directory
            File rs_editor = new File(targetDir(), "../../../editor/plugins");
            File rs_core = new File(targetDir(), "../../../core/plugins");
            
            if (args.length > 1) {
                rs_editor = new File(args[0]);
                rs_core = new File(args[1]);
            }

            LOG.info("Using editor plugins directory: {}", rs_editor.getAbsolutePath());
            LOG.info("Using core plugins directory: {}", rs_core.getAbsolutePath());

            if (!rs_editor.exists()) {
                fail("IDE editor plugins directory does not exist! " + rs_editor.getAbsolutePath());
            }
            if (!rs_editor.isDirectory()) {
                fail("IDE editor plugins directory is a file, not a directory! " + rs_editor.getAbsolutePath());
            }
            if (!rs_core.exists()) {
                fail("IDE core plugins directory does not exist! " + rs_core.getAbsolutePath());
            }
            if (!rs_core.isDirectory()) {
                fail("IDE core plugins directory is a file, not a directory! "  + rs_core.getAbsolutePath());
            }

            File archetypesDir = new File(rs_editor, "org.fusesource.ide.branding/archetypes");
            File xsdsDir = new File(rs_editor, "org.fusesource.ide.catalogs");
            File compDir = new File(rs_core, "org.fusesource.ide.camel.model/components");

            Downloader app = new Downloader(archetypesDir, xsdsDir, compDir);
            app.start();
            LOG.info("Indexer has started, now trying to find stuff");
            app.run();
            app.stop();
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
        }
    }

    protected static void fail(String message) {
        LOG.info(message);
        System.exit(1);
    }

    public Downloader() {
    }

    public Downloader(File archetypeDir, File xsdDir, File camelComponentMetaData) {
        this.archetypeDir = archetypeDir;
        this.xsdDir = xsdDir;
        this.camelComponentMetaData = camelComponentMetaData;
    }

    public static File targetDir() {
        String basedir = System.getProperty("basedir", ".");
        return new File(basedir + "/target");
    }

    public void start() throws Exception {
        indexer = new MavenIndexerFacade();
        String[] repositories = { "http://repository.jboss.org/nexus/content/groups/ea/", "http://repo1.maven.org/maven2" };
        indexer.setRepositories(repositories);
        indexer.setCacheDirectory(new File(targetDir(), "mavenIndexer"));
        indexer.start();

        List<Repository> repos = Aether.defaultRepositories();
        repos.add(new Repository("ea.repository.jboss.org", "http://repository.jboss.org/nexus/content/groups/ea"));
        aether = new Aether(Aether.USER_REPOSITORY, repos);
    }

    public void stop() throws Exception {
        indexer.destroy();
    }

    public void run() throws Exception {
        downloadArchetypes();
        downloadXsds();
        downloadCamelComponentData();
    }

    public void downloadArchetypes() throws IOException {
        if (delete) {
            FileUtils.deleteDirectory(archetypeDir);
            archetypeDir.mkdirs();
        }

        PrintWriter out = new PrintWriter(new FileWriter(new File(archetypeDir, "archetypes.xml")));
        out.println("<archetypes>");

        try {
            downloadArchetypesForGroup(out, "org.apache.camel.archetypes", System.getProperty("camel.version"));
            downloadArchetypesForGroup(out, "org.apache.cxf.archetype", System.getProperty("cxf.version"));
            downloadArchetypesForGroup(out, "io.fabric8", System.getProperty("fabric.version"));
        } catch (Exception ex) {
            LOG.error(ex.getMessage(), ex);
        } finally {
            out.println("</archetypes>");
            out.close();
        }

        LOG.info("Running git add...");
        ProcessBuilder pb = new ProcessBuilder("git", "add", "*");
        pb.directory(archetypeDir);
        pb.start();
    }

    protected void downloadArchetypesForGroup(PrintWriter out, String groupId, String version)
            throws Exception {
        String classifier = null;
        String packaging = "maven-archetype";

        List<ArtifactDTO> answer = indexer.search(groupId, "", "", packaging, classifier, null);
        for (ArtifactDTO artifact : answer) {
            if (ignoredArtifacts.contains(artifact.getArtifactId())) {
                LOG.debug("Ignored: {}", artifact.getArtifactId());
                continue;
            }
            out.println("<archetype groupId='" + artifact.getGroupId() + "' artifactId='" + artifact.getArtifactId() + "' version='" + version + "'>" + artifact.getDescription() + "</archetype>");
            downloadArtifact(artifact, version);
        }
        LOG.debug("Found " + answer.size() + " results for groupId " + groupId + ", version " + version);
    }

    public void downloadXsds() throws Exception {
        new DownloadLatestXsds(xsdDir, true).run();
    }

    /**
     * creates the camel component and parameter model and stores it in an xml in the model plugin
     * 
     * @throws IOException
     */
    public void downloadCamelComponentData() throws IOException {
        String version = System.getProperty("camel.version");

        File outputFile = new File(camelComponentMetaData, "components-" + version + ".xml");
        if (outputFile.exists() && outputFile.isFile()) outputFile.delete(); 
        
        CamelCatalog cat = new DefaultCamelCatalog();
        ObjectMapper mapper = new ObjectMapper();
        
        HashMap<String, ComponentModel> knownComponents = new HashMap<String, ComponentModel>();
        List<String> components = cat.findComponentNames();
        
        for (String compName : components) {
        	String json = cat.componentJSonSchema(compName);
        	
        	if (compName.equalsIgnoreCase("ftp") || 
        		compName.equalsIgnoreCase("ftps") ||
        		compName.equalsIgnoreCase("sftp")) {

        		// in 2.15.0 there is a bug in the description of the ftp components separator property: TODO: delete me when moving to 2.15.1 or higher
        		json = json.replace("Windows = Path separator \\ ", "Windows = Path separator \\\\");
        	}
        	
        	ComponentModel model = mapper.readValue(json, ComponentModel.class);
        	
        	String clazz = model.getComponent().getJavaType();
        	ComponentModel c = knownComponents.get(clazz);
            if (c == null) {
                c = model;
            } 
            c.getComponent().getSchemes().add(model.getComponent().getScheme());                        
            knownComponents.put(clazz, c);
        }

        PrintWriter out = new PrintWriter(new FileWriter(outputFile));
        out.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        out.println("<components>");
        
        Collection<ComponentModel> comps = knownComponents.values();
        for (ComponentModel compModel : comps) {
        	ComponentModel.Component comp = compModel.getComponent();
            out.println("   <component>");
            out.println("      <tags>");
            String[] tags = comp.getLabel().split(",");
            for (String tag : tags) {
            	out.println("         <tag>" + tag + "</tag>");
            }
            out.println("      </tags>");
            out.println("      <description>" + comp.getDescription() + "</description>");
            out.println("      <syntax>" + comp.getSyntax() + "</syntax>");
            out.println("      <class>" + comp.getJavaType() + "</class>");
            out.println("      <kind>" + comp.getKind() + "</kind>");
            if (comp.getConsumerOnly() != null) out.println("      <consumerOnly>" + comp.consumerOnly + "</consumerOnly>");
            if (comp.getProducerOnly() != null) out.println("      <producerOnly>" + comp.getProducerOnly() + "</producerOnly>");
            out.println("      <schemes>");
            for (String scheme : comp.getSchemes()) {
                out.println("           <scheme>" + scheme + "</scheme>");
            }
            out.println("       </schemes>");
            out.println("       <dependencies>");
            out.println("           <dependency>");
            out.println(String.format("             <groupId>%s</groupId>", comp.getGroupId()));
            out.println(String.format("             <artifactId>%s</artifactId>", comp.getArtifactId()));
            out.println(String.format("             <version>%s</version>", comp.getVersion()));
            out.println("           </dependency>");
            out.println("       </dependencies>");

            out.println("       <componentProperties>");
            for (ComponentParam p : compModel.getComponentParams()) {
                out.print("           <componentProperty name=\"" + p.getName() + "\" type=\"" + p.getType() + "\" javaType=\"" + p.getJavaType() + "\" kind=\"" + p.getKind() + "\" ");
                if (p.getChoiceString() != null) out.print("choice=\"" + p.getChoiceString() + "\" ");
                if (p.getDeprecated() != null) out.print("deprecated=\"" + p.getDeprecated() + "\" ");
                out.println("description=\"" + (p.getDescription() != null ? p.getDescription() : "") + "\"/>");
            }           
            out.println("       </componentProperties>");   
            
            out.println("       <uriParameters>");
            for (UriParam p : compModel.getUriParams()) {
                out.print("           <uriParameter name=\"" + p.getName() + "\" type=\"" + p.getType() + "\" javaType=\"" + p.getJavaType() + "\" kind=\"" + p.getKind() + "\" ");
                if (p.getChoiceString() != null) out.print("choice=\"" + p.getChoiceString() + "\" ");
                if (p.getDeprecated() != null) out.print("deprecated=\"" + p.getDeprecated() + "\" ");
                if (p.getDefaultValue() != null) out.print("defaultValue=\"" + p.getDefaultValue() + "\" ");
                if (p.getRequired() != null) out.print("required=\"" + p.getRequired() + "\" ");
                if (p.getLabel() != null) out.print("label=\"" + p.getLabel() + "\" ");
                out.println("description=\"" + (p.getDescription() != null ? p.getDescription() : "") + "\"/>");
            }           
            out.println("       </uriParameters>");            
            out.println("   </component>");
        }
        out.println("</components>");
        out.close();
    }

    public boolean isDelete() {
        return delete;
    }

    public void setDelete(boolean delete) {
        this.delete = delete;
    }

    public Aether getAether() {
        return aether;
    }

    public void setAether(Aether aether) {
        this.aether = aether;
    }

    public MavenIndexerFacade getIndexer() {
        return indexer;
    }

    public void setIndexer(MavenIndexerFacade indexer) {
        this.indexer = indexer;
    }

    public File getArchetypeDir() {
        return archetypeDir;
    }

    public void setArchetypeDir(File archetypeDir) {
        this.archetypeDir = archetypeDir;
    }

    public File getXsdDir() {
        return xsdDir;
    }

    public void setXsdDir(File xsdDir) {
        this.xsdDir = xsdDir;
    }

    protected void downloadArtifact(ArtifactDTO artifact, String version) {
        try {
            AetherResult result = aether.resolve(artifact.getGroupId(), artifact.getArtifactId(), version, "jar", null);
            if (result != null) {
                List<File> files = result.getResolvedFiles();
                if (files != null && files.size() > 0) {
                    File file = files.get(0);
                    //for (File file : files) {
                    File newFile = new File(archetypeDir, file.getName());
                    FileInputStream input = new FileInputStream(file);
                    FileOutputStream output = new FileOutputStream(newFile);
                    IOUtils.copy(input, output);
                    IOUtils.closeQuietly(input);
                    IOUtils.closeQuietly(output);
                    LOG.info("Copied {}", newFile.getPath());
                }
            }
        } catch (Exception ex) {
            LOG.error(ex.getMessage());
        }
    }
    
    public static class ComponentModel {
    	
    	public static class Component {
    		private String kind;
        	private String scheme;
        	private String syntax;
        	private String description;
        	private String label;  // tags
        	private String javaType; // class
        	private String groupId;
        	private String artifactId;
        	private String version;
        	private String producerOnly;
        	private String consumerOnly;
        	private ArrayList<String> schemes = new ArrayList<String>();
        	
        	
        	/**
			 * @return the consumerOnly
			 */
			public String getConsumerOnly() {
				return this.consumerOnly;
			}
			
			/**
			 * @param consumerOnly the consumerOnly to set
			 */
			public void setConsumerOnly(String consumerOnly) {
				this.consumerOnly = consumerOnly;
			}
        	
        	/**
			 * @return the producerOnly
			 */
			public String getProducerOnly() {
				return this.producerOnly;
			}
			
			/**
			 * @param producerOnly the producerOnly to set
			 */
			public void setProducerOnly(String producerOnly) {
				this.producerOnly = producerOnly;
			}
        	
    		/**
			 * @return the artifactId
			 */
			public String getArtifactId() {
				return this.artifactId;
			}
			
			/**
			 * @return the description
			 */
			public String getDescription() {
				return this.description;
			}
			
			/**
			 * @return the groupId
			 */
			public String getGroupId() {
				return this.groupId;
			}
			
			/**
			 * @return the javaType
			 */
			public String getJavaType() {
				return this.javaType;
			}
			
			/**
			 * @return the kind
			 */
			public String getKind() {
				return this.kind;
			}
			
			/**
			 * @return the label
			 */
			public String getLabel() {
				return this.label;
			}
			
			/**
			 * @return the scheme
			 */
			public String getScheme() {
				return this.scheme;
			}
			
			/**
			 * @return the syntax
			 */
			public String getSyntax() {
				return this.syntax;
			}
			
			/**
			 * @return the version
			 */
			public String getVersion() {
				return this.version;
			}
			
			/**
			 * @param artifactId the artifactId to set
			 */
			public void setArtifactId(String artifactId) {
				this.artifactId = artifactId;
			}
			
			/**
			 * @param description the description to set
			 */
			public void setDescription(String description) {
				this.description = description;
			}
			
			/**
			 * @param groupId the groupId to set
			 */
			public void setGroupId(String groupId) {
				this.groupId = groupId;
			}
			
			/**
			 * @param javaType the javaType to set
			 */
			public void setJavaType(String javaType) {
				this.javaType = javaType;
				this.javaType = this.javaType.replaceAll("<", "&lt;");
    			this.javaType = this.javaType.replaceAll(">", "&gt;");
			}
			
			/**
			 * @param kind the kind to set
			 */
			public void setKind(String kind) {
				this.kind = kind;
			}
			
			/**
			 * @param label the label to set
			 */
			public void setLabel(String label) {
				this.label = label;
			}
			
			/**
			 * @param scheme the scheme to set
			 */
			public void setScheme(String scheme) {
				this.scheme = scheme;
			}
			
			/**
			 * @param syntax the syntax to set
			 */
			public void setSyntax(String syntax) {
				this.syntax = syntax;
			}
			
			/**
			 * @param version the version to set
			 */
			public void setVersion(String version) {
				this.version = version;
			}
			
			/**
			 * @return the schemes
			 */
			public ArrayList<String> getSchemes() {
				return this.schemes;
			}
			
			/**
			 * @param schemes the schemes to set
			 */
			public void setSchemes(ArrayList<String> schemes) {
				this.schemes = schemes;
			}
    	}
    	
    	public static class ComponentParam {
    		private String name;
    		private String kind;
    		private String type;
    		private String javaType;
    		private String deprecated;
    		@JsonProperty("enum")
        	private String[] choice;
    		private String description;

    		/**
			 * @return the description
			 */
			public String getDescription() {
				return this.description;
			}
    		
    		/**
			 * @return the choice
			 */
			public String[] getChoice() {
				return this.choice;
			}
    		
    		/**
			 * @return the deprecated
			 */
			public String getDeprecated() {
				return this.deprecated;
			}
			
			/**
			 * @return the javaType
			 */
			public String getJavaType() {
				return this.javaType;
			}
			
			/**
			 * @return the kind
			 */
			public String getKind() {
				return this.kind;
			}
			
			/**
			 * @return the name
			 */
			public String getName() {
				return this.name;
			}
			
			/**
			 * @return the type
			 */
			public String getType() {
				return this.type;
			}
			
			/**
			 * @param deprecated the deprecated to set
			 */
			public void setDeprecated(String deprecated) {
				this.deprecated = deprecated;
			}
			
			/**
			 * @param javaType the javaType to set
			 */
			public void setJavaType(String javaType) {
				this.javaType = javaType;
				this.javaType = this.javaType.replaceAll("<", "&lt;");
    			this.javaType = this.javaType.replaceAll(">", "&gt;");
			}
			
			/**
			 * @param kind the kind to set
			 */
			public void setKind(String kind) {
				this.kind = kind;
			}
			
			/**
			 * @param name the name to set
			 */
			public void setName(String name) {
				this.name = name;
			}
			
			/**
			 * @param type the type to set
			 */
			public void setType(String type) {
				this.type = type;
			}
			
			/**
			 * @param choice the choice to set
			 */
			public void setChoice(String[] choice) {
				this.choice = choice;
			}
			
			/**
			 * @param description the description to set
			 */
			public void setDescription(String description) {
				this.description = description;
			}
			
			public String getChoiceString() {
    			if (this.choice == null || this.choice.length<1) return null;
    			String retVal = "";
    			for (String c : this.choice) {
    				if (retVal.length()>0) retVal += ","; 
    				retVal += c;
    			}
    			return retVal;
    		}
    	}
    	
    	public static class UriParam {
        	
            private String name;
        	private String kind;
        	private String required;
        	private String type;
        	private String javaType;
        	private String deprecated;
        	private String defaultValue;
            private String description;
        	private String label;
        	@JsonProperty("enum")
        	private String[] choice;

        	/**
			 * @return the choice
			 */
			public String[] getChoice() {
				return this.choice;
			}
			
			/**
			 * @param choice the choice to set
			 */
			public void setChoice(String[] choice) {
				this.choice = choice;
			}
        	
            /**
             * @return the name
             */
            public String getName() {
                return this.name;
            }
            
            /**
             * @param name the name to set
             */
            public void setName(String name) {
                this.name = name;
            }
            
            /**
             * @return the type
             */
            public String getType() {
                return this.type;
            }
            
            /**
             * @param type the type to set
             */
            public void setType(String type) {
                this.type = type;
            }
            
            /**
             * @return the defaultValue
             */
            public String getDefaultValue() {
                return this.defaultValue;
            }
            
            /**
             * @param defaultValue the defaultValue to set
             */
            public void setDefaultValue(String defaultValue) {
                this.defaultValue = defaultValue;
            }
            
            /**
             * @return the kind
             */
            public String getKind() {
                return this.kind;
            }
            
            /**
             * @param kind the kind to set
             */
            public void setKind(String kind) {
                this.kind = kind;
            }
            
            /**
             * @return the label
             */
            public String getLabel() {
                return this.label;
            }
            
            /**
             * @param label the label to set
             */
            public void setLabel(String label) {
                this.label = label;
            }
            
            /**
             * @return the description
             */
            public String getDescription() {
                return this.description;
            }

            /**
             * @param description the description to set
             */
            public void setDescription(String description) {
                this.description = description;
            }
            
            /**
    		 * @return the required
    		 */
    		public String getRequired() {
    			return this.required;
    		}
    		
    		/**
    		 * @param required the required to set
    		 */
    		public void setRequired(String required) {
    			this.required = required;
    		}
    		
    		/**
    		 * @return the deprecated
    		 */
    		public String getDeprecated() {
    			return this.deprecated;
    		}
    		
    		/**
    		 * @param deprecated the deprecated to set
    		 */
    		public void setDeprecated(String deprecated) {
    			this.deprecated = deprecated;
    		}
    		
    		/**
    		 * @return the javaType
    		 */
    		public String getJavaType() {
    			return this.javaType;
    		}
    		
    		/**
    		 * @param javaType the javaType to set
    		 */
    		public void setJavaType(String javaType) {
    			this.javaType = javaType;
    			this.javaType = this.javaType.replaceAll("<", "&lt;");
    			this.javaType = this.javaType.replaceAll(">", "&gt;");
    		}
    		
    		public String getChoiceString() {
    			if (this.choice == null || this.choice.length<1) return null;
    			String retVal = "";
    			for (String c : this.choice) {
    				if (retVal.length()>0) retVal += ","; 
    				retVal += c;
    			}
    			return retVal;
    		}
        }
    	
    	private Component component;
    	private HashMap<String, HashMap> componentProperties;
    	private HashMap<String, HashMap> properties;
    	private ArrayList<UriParam> uriParams = new ArrayList<Downloader.ComponentModel.UriParam>();
    	private ArrayList<ComponentParam> componentParams = new ArrayList<Downloader.ComponentModel.ComponentParam>();
    	
    	/**
		 * @return the component
		 */
		public Component getComponent() {
			return this.component;
		}
		
		/**
		 * @param component the component to set
		 */
		public void setComponent(Component component) {
			this.component = component;
		}
		
		/**
		 * @return the componentProperties
		 */
		public HashMap<String, HashMap> getComponentProperties() {
			return this.componentProperties;
		}
		
		/**
		 * @param componentProperties the componentProperties to set
		 */
		public void setComponentProperties(HashMap<String, HashMap> componentProperties) {
			this.componentProperties = componentProperties;
			generateComponentParamsModel();
		}
		
		/**
		 * @return the properties
		 */
		public HashMap<String, HashMap> getProperties() {
			return this.properties;
		}
		
		/**
		 * @param properties the properties to set
		 */
		public void setProperties(HashMap<String, HashMap> properties) {
			this.properties = properties;
			generateUriParamsModel();
		}
		
		/**
		 * @return the uriParams
		 */
		public ArrayList<UriParam> getUriParams() {
			return this.uriParams;
		}
		
		/**
		 * @return the componentParams
		 */
		public ArrayList<ComponentParam> getComponentParams() {
			return this.componentParams;
		}
		
		/**
		 * used to generate the list of uri params for this component
		 */
		private void generateUriParamsModel() {
			uriParams.clear();
			ObjectMapper mapper = new ObjectMapper();
			Iterator<String> it = properties.keySet().iterator();
			while (it.hasNext()) {
				String paramName = it.next();
				UriParam p = mapper.convertValue(properties.get(paramName), UriParam.class);
				p.setName(paramName);
				uriParams.add(p);
			}
		}
		
		/**
		 * used to generate the list of component params for this component
		 */
		private void generateComponentParamsModel() {
			componentParams.clear();
			ObjectMapper mapper = new ObjectMapper();
			Iterator<String> it = componentProperties.keySet().iterator();
			while (it.hasNext()) {
				String paramName = it.next();
				ComponentParam p = mapper.convertValue(componentProperties.get(paramName), ComponentParam.class);
				p.setName(paramName);
				componentParams.add(p);
			}
		}
    }
}
