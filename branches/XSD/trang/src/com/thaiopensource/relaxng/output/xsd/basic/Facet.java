package com.thaiopensource.relaxng.output.xsd.basic;

import com.thaiopensource.relaxng.edit.SourceLocation;
import com.thaiopensource.util.Equal;

public class Facet extends Located {
  private final String name;
  private final String value;
  private final String prefix;
  private final String namespace;

  public Facet(SourceLocation location, String name, String value) {
    this(location, name, value, null, null);
  }

  public Facet(SourceLocation location, String name, String value, String prefix, String namespace) {
    super(location);
    this.name = name;
    this.value = value;
    this.prefix = prefix;
    this.namespace = namespace;
  }

  public String getName() {
    return name;
  }

  public String getValue() {
    return value;
  }

  public String getPrefix() {
    return prefix;
  }

  public String getNamespace() {
    return namespace;
  }

  public boolean equals(Object obj) {
    if (!(obj instanceof Facet))
      return false;
    Facet other = (Facet)obj;
    return (this.name.equals(other.name)
            && this.value.equals(other.value)
            && Equal.equal(this.prefix, other.prefix)
            && Equal.equal(this.namespace, other.namespace));
  }

  public int hashCode() {
    return name.hashCode() ^ value.hashCode();
  }
}
