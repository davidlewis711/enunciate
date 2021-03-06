package com.webcohesion.enunciate.api;

import com.webcohesion.enunciate.api.datatype.Syntax;
import com.webcohesion.enunciate.api.resources.ResourceApi;
import com.webcohesion.enunciate.api.services.ServiceApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * @author Ryan Heaton
 */
public class ApiRegistry {

  private final List<ServiceApi> serviceApis = new ArrayList<ServiceApi>();
  private final List<ResourceApi> resourceApis = new ArrayList<ResourceApi>();
  private final Set<Syntax> syntaxes = new TreeSet<Syntax>();
  private InterfaceDescriptionFile swaggerUI;

  public List<ServiceApi> getServiceApis() {
    return serviceApis;
  }

  public List<ResourceApi> getResourceApis() {
    return resourceApis;
  }

  public Set<Syntax> getSyntaxes() {
    return syntaxes;
  }

  public InterfaceDescriptionFile getSwaggerUI() {
    return swaggerUI;
  }

  public void setSwaggerUI(InterfaceDescriptionFile swaggerUI) {
    this.swaggerUI = swaggerUI;
  }
}
