/*
 * Copyright 2006-2008 Web Cohesion
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.webcohesion.enunciate.modules.gwt_json_overlay;

import com.webcohesion.enunciate.EnunciateContext;
import com.webcohesion.enunciate.EnunciateException;
import com.webcohesion.enunciate.api.resources.MediaTypeDescriptor;
import com.webcohesion.enunciate.api.resources.Method;
import com.webcohesion.enunciate.api.resources.Resource;
import com.webcohesion.enunciate.api.resources.ResourceGroup;
import com.webcohesion.enunciate.artifacts.ArtifactType;
import com.webcohesion.enunciate.artifacts.ClientLibraryArtifact;
import com.webcohesion.enunciate.artifacts.FileArtifact;
import com.webcohesion.enunciate.facets.FacetFilter;
import com.webcohesion.enunciate.module.*;
import com.webcohesion.enunciate.modules.jackson.EnunciateJacksonContext;
import com.webcohesion.enunciate.modules.jackson.JacksonModule;
import com.webcohesion.enunciate.modules.jackson.model.TypeDefinition;
import com.webcohesion.enunciate.modules.jackson1.EnunciateJackson1Context;
import com.webcohesion.enunciate.modules.jackson1.Jackson1Module;
import com.webcohesion.enunciate.modules.jaxrs.JaxrsModule;
import com.webcohesion.enunciate.util.AntPatternMatcher;
import com.webcohesion.enunciate.util.freemarker.ClientPackageForMethod;
import com.webcohesion.enunciate.util.freemarker.FileDirective;
import com.webcohesion.enunciate.util.freemarker.IsFacetExcludedMethod;
import freemarker.cache.URLTemplateLoader;
import freemarker.core.Environment;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
import org.apache.commons.configuration.HierarchicalConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

/**
 * @author Ryan Heaton
 */
public class GWTJSONOverlayModule extends BasicGeneratingModule implements ApiFeatureProviderModule, ProjectExtensionModule {

  private static final String LIRBARY_DESCRIPTION_PROPERTY = "com.webcohesion.enunciate.modules.java_xml_client.EnunciateJavaJSONClientModule#LIRBARY_DESCRIPTION_PROPERTY";

  JacksonModule jacksonModule;
  Jackson1Module jackson1Module;
  JaxrsModule jaxrsModule;

  /**
   * @return "gwt-json-overlay"
   */
  @Override
  public String getName() {
    return "gwt-json-overlay";
  }

  @Override
  public List<DependencySpec> getDependencySpecifications() {
    return Arrays.asList((DependencySpec) new DependencySpec() {
      @Override
      public boolean accept(EnunciateModule module) {
        if (module instanceof JacksonModule) {
          jacksonModule = (JacksonModule) module;
          return true;
        }
        if (module instanceof Jackson1Module) {
          jackson1Module = (Jackson1Module) module;
          return true;
        }
        else if (module instanceof JaxrsModule) {
          jaxrsModule = (JaxrsModule) module;
          return true;
        }

        return module instanceof ApiRegistryProviderModule;
      }

      @Override
      public boolean isFulfilled() {
        return true;
      }

      @Override
      public String toString() {
        return "optional jackson, optional jackson1, optional jaxrs";
      }
    });
  }


  @Override
  public void call(EnunciateContext context) {
    if ((this.jacksonModule == null || this.jacksonModule.getJacksonContext() == null || this.jacksonModule.getJacksonContext().getTypeDefinitions().isEmpty()) &&
      (this.jackson1Module == null || this.jackson1Module.getJacksonContext() == null || this.jackson1Module.getJacksonContext().getTypeDefinitions().isEmpty()))
    {
      info("No Jackson JSON data types: GWT JSON overlays will be generated.");
      return;
    }

    File sourceDir = generateClientSources();
    packageArtifacts(sourceDir);
  }

  protected File generateClientSources() {
    File sourceDir = getSourceDir();
    sourceDir.mkdirs();

    Map<String, Object> model = new HashMap<String, Object>();

    Map<String, String> conversions = getClientPackageConversions();
    EnunciateJacksonContext jacksonContext = this.jacksonModule != null ? this.jacksonModule.getJacksonContext() : null;
    EnunciateJackson1Context jackson1Context = this.jackson1Module != null ? this.jackson1Module.getJacksonContext() : null;
    MergedJsonContext jsonContext = new MergedJsonContext(jacksonContext, jackson1Context);
    ClientClassnameForMethod classnameFor = new ClientClassnameForMethod(conversions, jsonContext);
    model.put("packageFor", new ClientPackageForMethod(conversions, this.context));
    model.put("classnameFor", classnameFor);
    model.put("simpleNameFor", new SimpleNameForMethod(classnameFor, jsonContext));
    model.put("isAccessorOfTypeLong", new IsAccessorOfTypeLongMethod());
    model.put("file", new FileDirective(sourceDir, this.enunciate.getLogger()));
    model.put("generatedCodeLicense", this.enunciate.getConfiguration().readGeneratedCodeLicenseFile());

    Set<String> facetIncludes = new TreeSet<String>(this.enunciate.getConfiguration().getFacetIncludes());
    facetIncludes.addAll(getFacetIncludes());
    Set<String> facetExcludes = new TreeSet<String>(this.enunciate.getConfiguration().getFacetExcludes());
    facetExcludes.addAll(getFacetExcludes());
    FacetFilter facetFilter = new FacetFilter(facetIncludes, facetExcludes);

    model.put("isFacetExcluded", new IsFacetExcludedMethod(facetFilter));

    AntPatternMatcher matcher = new AntPatternMatcher();
    matcher.setPathSeparator(".");

    boolean upToDate = isUpToDateWithSources(sourceDir);
    if (!upToDate) {
      try {
        debug("Generating the GWT JSON Overlay...");

        if (jacksonContext != null) {
          for (TypeDefinition typeDefinition : jacksonContext.getTypeDefinitions()) {
            if (facetFilter.accept(typeDefinition)) {
              model.put("type", typeDefinition);
              URL template = typeDefinition.isEnum() ? getTemplateURL("gwt-enum-type.fmt") : getTemplateURL("gwt-type.fmt");
              processTemplate(template, model);
            }
          }
        }

        if (jackson1Context != null) {
          for (com.webcohesion.enunciate.modules.jackson1.model.TypeDefinition typeDefinition : jackson1Context.getTypeDefinitions()) {
            if (facetFilter.accept(typeDefinition)) {
              model.put("type", typeDefinition);
              URL template = typeDefinition.isEnum() ? getTemplateURL("gwt-enum-type.fmt") : getTemplateURL("gwt-type.fmt");
              processTemplate(template, model);
            }
          }
        }
      }
      catch (IOException e) {
        throw new EnunciateException(e);
      }
      catch (TemplateException e) {
        throw new EnunciateException(e);
      }
    }
    else {
      info("Skipping generation of GWT JSON Overlay as everything appears up-to-date...");
    }

    context.setProperty(LIRBARY_DESCRIPTION_PROPERTY, readLibraryDescription(model));

    return sourceDir;
  }

  protected File getSourceDir() {
    return new File(new File(this.enunciate.getBuildDir(), getName()), "src");
  }

  /**
   * Processes the specified template with the given model.
   *
   * @param templateURL The template URL.
   * @param model       The root model.
   */
  public String processTemplate(URL templateURL, Object model) throws IOException, TemplateException {
    debug("Processing template %s.", templateURL);
    Configuration configuration = new Configuration(Configuration.VERSION_2_3_22);

    configuration.setTemplateLoader(new URLTemplateLoader() {
      protected URL getURL(String name) {
        try {
          return new URL(name);
        }
        catch (MalformedURLException e) {
          return null;
        }
      }
    });

    configuration.setTemplateExceptionHandler(new TemplateExceptionHandler() {
      public void handleTemplateException(TemplateException templateException, Environment environment, Writer writer) throws TemplateException {
        throw templateException;
      }
    });

    configuration.setLocalizedLookup(false);
    configuration.setDefaultEncoding("UTF-8");
    configuration.setObjectWrapper(new GWTJSONOverlayObjectWrapper());
    Template template = configuration.getTemplate(templateURL.toString());
    StringWriter unhandledOutput = new StringWriter();
    template.process(model, unhandledOutput);
    unhandledOutput.close();
    return unhandledOutput.toString();
  }

  protected File packageArtifacts(File sourceDir) {
    File packageDir = getPackageDir();
    packageDir.mkdirs();

    try {
      String jarName = getJarName();
      File jarFile = new File(packageDir, jarName);
      if (!isUpToDateWithSources(jarFile)) {
        boolean anyFiles = this.enunciate.zip(jarFile, sourceDir);
        if (!anyFiles) {
          jarFile = null;
        }
      }
      else {
        info("Skipping creation of the GWT overlay source jar as everything appears up-to-date...");
      }

      ClientLibraryArtifact artifactBundle = new ClientLibraryArtifact(getName(), "gwt.json.overlay", "GWT JSON Overlay");
      artifactBundle.setPlatform("Google Web Toolkit");
      //read in the description from file:
      artifactBundle.setDescription((String) context.getProperty(LIRBARY_DESCRIPTION_PROPERTY));
      FileArtifact sourcesJar = new FileArtifact(getName(), "gwt.json.overlay.sources", jarFile);
      sourcesJar.setDescription("The sources for the GWT JSON overlay.");
      sourcesJar.setPublic(false);
      sourcesJar.setArtifactType(ArtifactType.sources);
      artifactBundle.addArtifact(sourcesJar);
      this.enunciate.addArtifact(sourcesJar);
      this.enunciate.addArtifact(artifactBundle);
    }
    catch (IOException e) {
      throw new EnunciateException(e);
    }

    return packageDir;
  }

  protected File getPackageDir() {
    return new File(new File(this.enunciate.getBuildDir(), getName()), "build");
  }

  /**
   * Reads a resource into string form.
   *
   * @return The string form of the resource.
   */
  protected String readLibraryDescription(Map<String, Object> model) {
    model.put("sample_resource", findExampleResourceMethod());

    URL res = GWTJSONOverlayModule.class.getResource("library_description.fmt");
    try {
      return processTemplate(res, model);
    }
    catch (TemplateException e) {
      throw new EnunciateException(e);
    }
    catch (IOException e) {
      throw new EnunciateException(e);
    }
  }

  /**
   * Finds an example resource method, according to the following preference order:
   *
   * <ol>
   * <li>The first method annotated with {@link com.webcohesion.enunciate.metadata.DocumentationExample}.
   * <li>The first method with BOTH an output payload with a known XML element and an input payload with a known XML element.
   * <li>The first method with an output payload with a known XML element.
   * </ol>
   *
   * @return An example resource method, or if no good examples were found.
   */
  public Method findExampleResourceMethod() {
    Method example = null;
    List<ResourceGroup> resourceGroups = this.jaxrsModule.getJaxrsContext().getResourceGroups();
    for (ResourceGroup resourceGroup : resourceGroups) {
      List<Resource> resources = resourceGroup.getResources();
      for (Resource resource : resources) {
        for (Method method : resource.getMethods()) {
          if (hasXmlResponseEntity(method)) {
            if (hasXmlRequestEntity(method)) {
              //we'll prefer one with both an output AND an input.
              return method;
            }
            else {
              //we'll prefer the first one we find with an output.
              example = example == null ? method : example;
            }
          }
        }
      }
    }

    return example;
  }

  private boolean hasXmlResponseEntity(Method method) {
    if (method.getResponseEntity() != null) {
      for (MediaTypeDescriptor mediaTypeDescriptor : method.getResponseEntity().getMediaTypes()) {
        String syntax = mediaTypeDescriptor.getSyntax();
        if (EnunciateJacksonContext.SYNTAX_LABEL.equals(syntax) || EnunciateJackson1Context.SYNTAX_LABEL.equals(syntax)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean hasXmlRequestEntity(Method method) {
    if (method.getRequestEntity() != null) {
      for (MediaTypeDescriptor mediaTypeDescriptor : method.getRequestEntity().getMediaTypes()) {
        String syntax = mediaTypeDescriptor.getSyntax();
        if (EnunciateJacksonContext.SYNTAX_LABEL.equals(syntax) || EnunciateJackson1Context.SYNTAX_LABEL.equals(syntax)) {
          return true;
        }
      }
    }
    return false;
  }

  protected URL getTemplateURL(String template) {
    return GWTJSONOverlayModule.class.getResource(template);
  }

  public String getJarName() {
    return this.config.getString("[@jarName]", getSlug() + "-gwt-json-overlay.jar");
  }

  public Map<String, String> getClientPackageConversions() {
    List<HierarchicalConfiguration> conversionElements = this.config.configurationsAt("package-conversions.convert");
    HashMap<String, String> conversions = new HashMap<String, String>();
    for (HierarchicalConfiguration conversionElement : conversionElements) {
      conversions.put(conversionElement.getString("[@from]"), conversionElement.getString("[@to]"));
    }
    return conversions;
  }

  public String getSlug() {
    return this.config.getString("[@slug]", this.enunciate.getConfiguration().getSlug());
  }

  public List<File> getProjectSources() {
    return Collections.emptyList();
  }

  public List<File> getProjectTestSources() {
    return Arrays.asList(getSourceDir());
  }

  public List<File> getProjectResourceDirectories() {
    return Collections.emptyList();
  }

  public List<File> getProjectTestResourceDirectories() {
    return Collections.emptyList();
  }

  public Set<String> getFacetIncludes() {
    List<Object> includes = this.config.getList("facets.include[@name]");
    Set<String> facetIncludes = new TreeSet<String>();
    for (Object include : includes) {
      facetIncludes.add(String.valueOf(include));
    }
    return facetIncludes;
  }

  public Set<String> getFacetExcludes() {
    List<Object> excludes = this.config.getList("facets.exclude[@name]");
    Set<String> facetExcludes = new TreeSet<String>();
    for (Object exclude : excludes) {
      facetExcludes.add(String.valueOf(exclude));
    }
    return facetExcludes;
  }
}
