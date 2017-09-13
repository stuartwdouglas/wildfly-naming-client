/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2017 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.naming.client;

import static java.security.AccessController.doPrivileged;
import static org.wildfly.naming.client.util.EnvironmentUtils.CONNECT_OPTIONS;
import static org.wildfly.naming.client.util.EnvironmentUtils.EJB_HOST_KEY;
import static org.wildfly.naming.client.util.EnvironmentUtils.EJB_PORT_KEY;
import static org.wildfly.naming.client.util.EnvironmentUtils.EJB_REMOTE_CONNECTIONS;
import static org.wildfly.naming.client.util.EnvironmentUtils.EJB_REMOTE_CONNECTION_PREFIX;
import static org.wildfly.naming.client.util.EnvironmentUtils.EJB_REMOTE_CONNECTION_PROVIDER_PREFIX;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import javax.naming.Binding;
import javax.naming.CompositeName;
import javax.naming.Context;
import javax.naming.InvalidNameException;
import javax.naming.Name;
import javax.naming.NameClassPair;
import javax.naming.NameParser;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.ModificationItem;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.spi.NamingManager;

import org.wildfly.common.Assert;
import org.wildfly.common.expression.Expression;
import org.wildfly.discovery.Discovery;
import org.wildfly.discovery.FilterSpec;
import org.wildfly.discovery.ServiceType;
import org.wildfly.discovery.ServicesQueue;
import org.wildfly.naming.client._private.Messages;
import org.wildfly.naming.client.util.EnvironmentUtils;
import org.wildfly.naming.client.util.FastHashtable;
import org.wildfly.naming.client.util.NamingUtils;
import org.xnio.Options;

/**
 * A root context which locates providers based on the {@link Context#PROVIDER_URL} environment property as well as any
 * URL scheme which appears as a part of the JNDI name in the first segment.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 * @author <a href="mailto:tadamski@redhat.com">Tomasz Adamski</a>
 */
public final class WildFlyRootContext implements DirContext {

    public static final ServiceType EJB_SERVICE_TYPE = ServiceType.of("ejb", "jboss");

    /**
     * The discovery attribute name which contains a cluster name.
     */
    public static final String FILTER_ATTR_CLUSTER = "cluster";

    private static final String CLUSTER_AFFINITY = "cluster-affinity";

    static {
        Version.getVersion();
    }
    private static final NameParser NAME_PARSER = CompositeName::new;

    private final FastHashtable<String, Object> environment;

    private final List<NamingProviderFactory> namingProviderFactories;
    private final List<NamingContextFactory> namingContextFactories;

    /**
     * Construct a new instance, searching the thread context class loader for providers.  If no context class loader is
     * set when this constructor is called, the class loader of this class is used.
     *
     * @param environment the environment to use (not copied)
     */
    public WildFlyRootContext(final FastHashtable<String, Object> environment) {
        this(environment, secureGetContextClassLoader());
    }

    /**
     * Construct a new instance, searching the given class loader for providers.
     *
     * @param environment the environment to use (not copied)
     * @param classLoader the class loader to search for providers
     */
    public WildFlyRootContext(final FastHashtable<String, Object> environment, final ClassLoader classLoader) {
        this.environment = environment;
        namingProviderFactories = loadServices(NamingProviderFactory.class, classLoader);
        namingContextFactories = loadServices(NamingContextFactory.class, classLoader);
    }

    static <T> List<T> loadServices(Class<T> type, ClassLoader classLoader) {
        return doPrivileged((PrivilegedAction<List<T>>) () -> {
            ArrayList<T> list = new ArrayList<>();
            Iterator<T> iterator = ServiceLoader.load(type, classLoader).iterator();
            for (;;) try {
                if (! iterator.hasNext()) break;
                final T contextFactory = iterator.next();
                list.add(contextFactory);
            } catch (ServiceConfigurationError error) {
                Messages.log.serviceConfigFailed(error);
            }
            list.trimToSize();
            return list;
        });
    }

    private WildFlyRootContext(final FastHashtable<String, Object> environment, final List<NamingProviderFactory> namingProviderFactories, final List<NamingContextFactory> namingContextFactories) {
        this.environment = environment;
        this.namingProviderFactories = namingProviderFactories;
        this.namingContextFactories = namingContextFactories;
    }

    private static ClassLoader secureGetContextClassLoader() {
        final ClassLoader contextClassLoader;
        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            contextClassLoader = doPrivileged((PrivilegedAction<ClassLoader>) WildFlyRootContext::getContextClassLoader);
        } else {
            contextClassLoader = getContextClassLoader();
        }
        return contextClassLoader == null ? WildFlyRootContext.class.getClassLoader() : contextClassLoader;
    }

    private static ClassLoader getContextClassLoader() {
        return Thread.currentThread().getContextClassLoader();
    }

    @Override
    public Object lookup(final String name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(getNameParser().parse(name));
        if (reparsedName.isEmpty()) {
            return new WildFlyRootContext(environment.clone(), namingProviderFactories, namingContextFactories);
        }
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return result.context.lookup(name);
        } else {
            return result.context.lookup(reparsedName.getName());
        }
    }

    @Override
    public Object lookup(Name name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(name);
        if (reparsedName.isEmpty()) {
            return new WildFlyRootContext(environment.clone(), namingProviderFactories, namingContextFactories);
        }
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return result.context.lookup(name);
        } else {
            return result.context.lookup(reparsedName.getName());
        }
    }

    @Override
    public void bind(final String name, final Object obj) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(getNameParser().parse(name));
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            result.context.bind(name, obj);
        } else {
            result.context.bind(reparsedName.getName(), obj);
        }
    }

    @Override
    public void bind(final Name name, final Object obj) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(name);
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            result.context.bind(name, obj);
        } else {
            result.context.bind(reparsedName.getName(), obj);
        }
    }

    @Override
    public void rebind(final String name, final Object obj) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(getNameParser().parse(name));
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            result.context.rebind(name, obj);
        } else {
            result.context.rebind(reparsedName.getName(), obj);
        }
    }

    @Override
    public void rebind(final Name name, final Object obj) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(name);
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            result.context.rebind(name, obj);
        } else {
            result.context.rebind(reparsedName.getName(), obj);
        }
    }

    @Override
    public void unbind(final String name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(getNameParser().parse(name));
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            result.context.unbind(name);
        } else {
            result.context.unbind(reparsedName.getName());
        }
    }

    @Override
    public void unbind(final Name name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(name);
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            result.context.unbind(name);
        } else {
            result.context.unbind(reparsedName.getName());
        }
    }

    @Override
    public void rename(final String oldName, final String newName) throws NamingException {
        Assert.checkNotNullParam("oldName", oldName);
        Assert.checkNotNullParam("newName", newName);
        final ReparsedName oldReparsedName = reparse(getNameParser().parse(oldName));
        final ReparsedName newReparsedName = reparse(getNameParser().parse(newName));
        ContextResult result = getProviderContext(oldReparsedName.getUrlScheme());
        if(result.oldStyle) {
            result.context.rename(oldName, newName);
        } else {
            result.context.rename(oldReparsedName.getName(), newReparsedName.getName());
        }
    }

    @Override
    public void rename(final Name oldName, final Name newName) throws NamingException {
        Assert.checkNotNullParam("oldName", oldName);
        Assert.checkNotNullParam("newName", newName);
        final ReparsedName oldReparsedName = reparse(oldName);
        final ReparsedName newReparsedName = reparse(newName);
        ContextResult result = getProviderContext(oldReparsedName.getUrlScheme());
        if(result.oldStyle) {
            result.context.rename(oldName, newName);
        } else {
            result.context.rename(oldReparsedName.getName(), newReparsedName.getName());
        }
    }

    @Override
    public NamingEnumeration<NameClassPair> list(final String name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(getNameParser().parse(name));
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return CloseableNamingEnumeration.fromEnumeration(getProviderContext(reparsedName.getUrlScheme()).context.list(
                    name));
        } else {
            return CloseableNamingEnumeration.fromEnumeration(getProviderContext(reparsedName.getUrlScheme()).context.list(
                    reparsedName.getName()));
        }
    }

    @Override
    public NamingEnumeration<NameClassPair> list(final Name name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(name);
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return CloseableNamingEnumeration.fromEnumeration(getProviderContext(reparsedName.getUrlScheme()).context.list(
                    name));
        } else {
            return CloseableNamingEnumeration.fromEnumeration(getProviderContext(reparsedName.getUrlScheme()).context.list(
                    reparsedName.getName()));
        }
    }

    @Override
    public NamingEnumeration<Binding> listBindings(final String name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(getNameParser().parse(name));
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return CloseableNamingEnumeration.fromEnumeration(getProviderContext(reparsedName.getUrlScheme()).context.listBindings(
                    name));
        } else {
            return CloseableNamingEnumeration.fromEnumeration(getProviderContext(reparsedName.getUrlScheme()).context.listBindings(
                    reparsedName.getName()));
        }
    }

    @Override
    public NamingEnumeration<Binding> listBindings(final Name name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(name);
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return CloseableNamingEnumeration.fromEnumeration(getProviderContext(reparsedName.getUrlScheme()).context.listBindings(
                    name));
        } else {
            return CloseableNamingEnumeration.fromEnumeration(getProviderContext(reparsedName.getUrlScheme()).context.listBindings(
                    reparsedName.getName()));
        }
    }

    @Override
    public void destroySubcontext(final String name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(getNameParser().parse(name));
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            result.context.destroySubcontext(name);
        } else {
            result.context.destroySubcontext(reparsedName.getName());
        }
    }

    @Override
    public void destroySubcontext(final Name name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(name);
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            result.context.destroySubcontext(name);
        } else {
            result.context.destroySubcontext(reparsedName.getName());
        }
    }

    @Override
    public Context createSubcontext(final String name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(getNameParser().parse(name));
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return result.context.createSubcontext(name);
        } else {
            return result.context.createSubcontext(reparsedName.getName());
        }
    }

    @Override
    public Context createSubcontext(final Name name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(name);
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return result.context.createSubcontext(name);
        } else {
            return result.context.createSubcontext(reparsedName.getName());
        }
    }

    @Override
    public Object lookupLink(final String name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(getNameParser().parse(name));
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return result.context.lookupLink(name);
        } else {
            return result.context.lookupLink(reparsedName.getName());
        }
    }

    @Override
    public Object lookupLink(final Name name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(name);
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return result.context.lookupLink(name);
        } else {
            return result.context.lookupLink(reparsedName.getName());
        }
    }


    @Override
    public Attributes getAttributes(Name name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(name);
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return ((DirContext)result.context).getAttributes(name);
        } else {
            return ((DirContext)result.context).getAttributes(reparsedName.getName());
        }
    }

    @Override
    public Attributes getAttributes(String name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(getNameParser().parse(name));
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return ((DirContext)result.context).getAttributes(name);
        } else {
            return ((DirContext)result.context).getAttributes(reparsedName.getName());
        }
    }

    @Override
    public Attributes getAttributes(Name name, String[] attrIds) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(name);
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return ((DirContext)result.context).getAttributes(name, attrIds);
        } else {
            return ((DirContext)result.context).getAttributes(reparsedName.getName(), attrIds);
        }
    }

    @Override
    public Attributes getAttributes(String name, String[] attrIds) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(getNameParser().parse(name));
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return ((DirContext)result.context).getAttributes(name, attrIds);
        } else {
            return ((DirContext)result.context).getAttributes(reparsedName.getName(), attrIds);
        }
    }

    @Override
    public void modifyAttributes(Name name, int mod_op, Attributes attrs) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(name);
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            ((DirContext)result.context).modifyAttributes(name, mod_op, attrs);
        } else {
            ((DirContext)result.context).modifyAttributes(reparsedName.getName(), mod_op, attrs);
        }
    }

    @Override
    public void modifyAttributes(String name, int mod_op, Attributes attrs) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(getNameParser().parse(name));
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            ((DirContext)result.context).modifyAttributes(name, mod_op, attrs);
        } else {
            ((DirContext)result.context).modifyAttributes(reparsedName.getName(), mod_op, attrs);
        }
    }

    @Override
    public void modifyAttributes(Name name, ModificationItem[] mods) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(name);
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            ((DirContext)result.context).modifyAttributes(name, mods);
        } else {
            ((DirContext)result.context).modifyAttributes(reparsedName.getName(), mods);
        }
    }

    @Override
    public void modifyAttributes(String name, ModificationItem[] mods) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(getNameParser().parse(name));
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            ((DirContext)result.context).modifyAttributes(name, mods);
        } else {
            ((DirContext)result.context).modifyAttributes(reparsedName.getName(), mods);
        }
    }

    @Override
    public void bind(Name name, Object obj, Attributes attrs) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(name);
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            ((DirContext)result.context).bind(name, obj, attrs);
        } else {
            ((DirContext)result.context).bind(reparsedName.getName(), obj, attrs);
        }
    }

    @Override
    public void bind(String name, Object obj, Attributes attrs) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(getNameParser().parse(name));
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            ((DirContext)result.context).bind(name, obj, attrs);
        } else {
            ((DirContext)result.context).bind(reparsedName.getName(), obj, attrs);
        }
    }

    @Override
    public void rebind(Name name, Object obj, Attributes attrs) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(name);
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            ((DirContext)result.context).rebind(name, obj, attrs);
        } else {
            ((DirContext)result.context).rebind(reparsedName.getName(), obj, attrs);
        }
    }

    @Override
    public void rebind(String name, Object obj, Attributes attrs) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(getNameParser().parse(name));
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            ((DirContext)result.context).rebind(name, obj, attrs);
        } else {
            ((DirContext)result.context).rebind(reparsedName.getName(), obj, attrs);
        }
    }

    @Override
    public DirContext createSubcontext(Name name, Attributes attrs) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(name);
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return ((DirContext)result.context).createSubcontext(name, attrs);
        } else {
            return ((DirContext)result.context).createSubcontext(reparsedName.getName(), attrs);
        }
    }

    @Override
    public DirContext createSubcontext(String name, Attributes attrs) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(getNameParser().parse(name));
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return ((DirContext)result.context).createSubcontext(name, attrs);
        } else {
            return ((DirContext)result.context).createSubcontext(reparsedName.getName(), attrs);
        }
    }

    @Override
    public DirContext getSchema(Name name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(name);
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return ((DirContext)result.context).getSchema(name);
        } else {
            return ((DirContext)result.context).getSchema(reparsedName.getName());
        }
    }

    @Override
    public DirContext getSchema(String name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(getNameParser().parse(name));
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return ((DirContext)result.context).getSchema(name);
        } else {
            return ((DirContext)result.context).getSchema(reparsedName.getName());
        }
    }

    @Override
    public DirContext getSchemaClassDefinition(Name name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(name);
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return ((DirContext)result.context).getSchemaClassDefinition(name);
        } else {
            return ((DirContext)result.context).getSchemaClassDefinition(reparsedName.getName());
        }
    }

    @Override
    public DirContext getSchemaClassDefinition(String name) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(getNameParser().parse(name));
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return ((DirContext)result.context).getSchemaClassDefinition(name);
        } else {
            return ((DirContext)result.context).getSchemaClassDefinition(reparsedName.getName());
        }
    }

    @Override
    public NamingEnumeration<SearchResult> search(Name name, Attributes matchingAttributes, String[] attributesToReturn) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(name);
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return ((DirContext)result.context).search(name, matchingAttributes, attributesToReturn);
        } else {
            return ((DirContext)result.context).search(reparsedName.getName(), matchingAttributes, attributesToReturn);
        }
    }

    @Override
    public NamingEnumeration<SearchResult> search(String name, Attributes matchingAttributes, String[] attributesToReturn) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(getNameParser().parse(name));
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return ((DirContext)result.context).search(name, matchingAttributes, attributesToReturn);
        } else {
            return ((DirContext)result.context).search(reparsedName.getName(), matchingAttributes, attributesToReturn);
        }
    }

    @Override
    public NamingEnumeration<SearchResult> search(Name name, Attributes matchingAttributes) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(name);
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return ((DirContext)result.context).search(name, matchingAttributes);
        } else {
            return ((DirContext)result.context).search(reparsedName.getName(), matchingAttributes);
        }
    }

    @Override
    public NamingEnumeration<SearchResult> search(String name, Attributes matchingAttributes) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(getNameParser().parse(name));
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return ((DirContext)result.context).search(name, matchingAttributes);
        } else {
            return ((DirContext)result.context).search(reparsedName.getName(), matchingAttributes);
        }
    }

    @Override
    public NamingEnumeration<SearchResult> search(Name name, String filter, SearchControls cons) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(name);
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return ((DirContext)result.context).search(name, filter, cons);
        } else {
            return ((DirContext)result.context).search(reparsedName.getName(), filter, cons);
        }
    }

    @Override
    public NamingEnumeration<SearchResult> search(String name, String filter, SearchControls cons) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(getNameParser().parse(name));
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return ((DirContext)result.context).search(name, filter, cons);
        } else {
            return ((DirContext)result.context).search(reparsedName.getName(), filter, cons);
        }
    }

    @Override
    public NamingEnumeration<SearchResult> search(Name name, String filterExpr, Object[] filterArgs, SearchControls cons) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(name);
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return ((DirContext)result.context).search(name, filterExpr, filterArgs, cons);
        } else {
            return ((DirContext)result.context).search(reparsedName.getName(), filterExpr, filterArgs, cons);
        }
    }

    @Override
    public NamingEnumeration<SearchResult> search(String name, String filterExpr, Object[] filterArgs, SearchControls cons) throws NamingException {
        Assert.checkNotNullParam("name", name);
        final ReparsedName reparsedName = reparse(getNameParser().parse(name));
        ContextResult result = getProviderContext(reparsedName.getUrlScheme());
        if(result.oldStyle) {
            return ((DirContext)result.context).search(name, filterExpr, filterArgs, cons);
        } else {
            return ((DirContext)result.context).search(reparsedName.getName(), filterExpr, filterArgs, cons);
        }
    }

    @Override
    public NameParser getNameParser(Name name) throws NamingException {
        return getNameParser();
    }

    @Override
    public NameParser getNameParser(String s) throws NamingException {
        return getNameParser();
    }

    private NameParser getNameParser(){
        return NAME_PARSER;
    }

    public String composeName(final String name, final String prefix) throws NamingException {
        Assert.checkNotNullParam("name", name);
        Assert.checkNotNullParam("prefix", prefix);
        return composeName(getNameParser().parse(name), getNameParser().parse(prefix)).toString();
    }

    public Name composeName(final Name name, final Name prefix) throws NamingException {
        return prefix.addAll(name);
    }

    public Object addToEnvironment(final String propName, final Object propVal) {
        return environment.put(propName, propVal);
    }

    public Object removeFromEnvironment(final String propName) {
        return environment.remove(propName);
    }

    @Override
    public FastHashtable<String, Object> getEnvironment() throws NamingException {
        return environment;
    }

    public void close() throws NamingException {
    }

    public String getNameInNamespace() throws NamingException {
        // always root
        return "";
    }

    private ContextResult getProviderContext(final String nameScheme) throws NamingException {
        // get provider scheme
        final List<URI> providerUris = getProviderUris();
        if (providerUris == null || providerUris.stream().map(URI::getScheme).allMatch(scheme -> scheme == null || scheme.isEmpty())) {
            // search for context factories which support a null provider
            for (NamingContextFactory contextFactory : namingContextFactories) {
                if (contextFactory.supportsUriScheme(null, nameScheme)) {
                    return new ContextResult(contextFactory.createRootContext(null, nameScheme, getEnvironment()), false);
                }
            }
            if (nameScheme != null) {
                // there is a name scheme to resolve; try the old-fashioned way
                final Context context = NamingManager.getURLContext(nameScheme, environment);
                if (context != null) {
                    return new ContextResult(context, true);
                }
            }
            // by default, support an empty local root context
            return new ContextResult(NamingUtils.emptyContext(getEnvironment()), false);
        }
        String uriScheme = nameScheme;
        boolean supportsUriSchemes = false;
        // get active naming providers
        for (NamingProviderFactory providerFactory : namingProviderFactories) {
            supportsUriSchemes = true;
            for (URI providerUri : providerUris) {
                if (! providerFactory.supportsUriScheme(providerUri.getScheme(), getEnvironment())) {
                    supportsUriSchemes = false;
                    uriScheme = providerUri.getScheme();
                    break;
                }
            }
            if (supportsUriSchemes) {
                final NamingProvider provider = providerFactory.createProvider(getEnvironment(), providerUris.toArray(new URI[providerUris.size()]));
                for (NamingContextFactory contextFactory : namingContextFactories) {
                    if (contextFactory.supportsUriScheme(provider, nameScheme)) {
                        return new ContextResult(contextFactory.createRootContext(provider, nameScheme, getEnvironment()), false);
                    }
                }
            }
        }
        if (nameScheme != null) {
            // there is a name scheme to resolve; try the old-fashioned way
            final Context context = NamingManager.getURLContext(nameScheme, environment);
            if (context != null) {
                return new ContextResult(context, true);
            }
        }

        if (!supportsUriSchemes) {
            throw Messages.log.invalidURLSchemeName(uriScheme);
        }

        throw Messages.log.noProviderForUri(nameScheme);
    }

    /**
     * Get the provider URI list either from the context property or from the old EJB remote connections configuration.
     *
     * @return the provider URI list
     */
    private List<URI> getProviderUris() throws NamingException {
        final FastHashtable<String, Object> env = getEnvironment();
        Object cluster = env.get(CLUSTER_AFFINITY);
        if(cluster != null) {
            List<URI> ret = new ArrayList<>();
            try (final ServicesQueue queue = Discovery.getContextManager().get().discover(
                    EJB_SERVICE_TYPE, FilterSpec.equal(FILTER_ATTR_CLUSTER, cluster.toString())
            )) {
                ret.add(queue.take());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return ret;
        }


        Object urlString = env.get(Context.PROVIDER_URL);
        if (urlString != null) {
            String providerUrl = Expression.compile(urlString.toString(), Expression.Flag.LENIENT_SYNTAX).evaluateWithPropertiesAndEnvironment(false);
            if (! providerUrl.isEmpty()) {
                final String[] urls = providerUrl.split(",");
                final List<URI> providerUris = new ArrayList<>(urls.length);
                for (String url : urls) {
                    URI providerUri;
                    try {
                        providerUri = new URI(url.trim());
                    } catch (URISyntaxException e) {
                        throw Messages.log.invalidProviderUri(e, url);
                    }
                    providerUris.add(providerUri);
                }
                return providerUris;
            }
        }
        // fall back to EJB connection properties
        final String connectionNameList = ((String) env.getOrDefault(EJB_REMOTE_CONNECTIONS, "")).trim();
        if (! connectionNameList.isEmpty()) {

            // Cleanup Context.URL_PKG_PREFIXES in order to avoid possible side effects due to legacy package prefix
            getEnvironment().remove(Context.URL_PKG_PREFIXES);

            Messages.log.deprecatedProperties();
            final String[] names = connectionNameList.split("\\s*,\\s*");
            final List<URI> uriList = new ArrayList<>(names.length);
            for (String connectionName : names) {
                connectionName = connectionName.trim();
                // attempt to determine the PROVIDER_URL from the EJB HOST and PORT properties for each
                final String ejbPrefix = EJB_REMOTE_CONNECTION_PREFIX + connectionName + ".";
                final String host = getStringProperty(ejbPrefix + EJB_HOST_KEY, env);
                final String port = getStringProperty(ejbPrefix + EJB_PORT_KEY, env);
                String sslEnabled = getStringProperty(ejbPrefix + CONNECT_OPTIONS + Options.SSL_ENABLED, env);
                if (sslEnabled == null) {
                    sslEnabled = getStringProperty(EJB_REMOTE_CONNECTION_PROVIDER_PREFIX + Options.SSL_ENABLED, env);
                }
                String protocol = getStringProperty(ejbPrefix + "protocol", env);
                if (protocol == null) {
                    if (Boolean.parseBoolean(sslEnabled)) {
                        protocol = "remote+https";
                    } else {
                        protocol = "remote+http";
                    }
                }
                if (host != null && port != null) {
                    String realHost = Expression.compile(host, Expression.Flag.LENIENT_SYNTAX).evaluateWithPropertiesAndEnvironment(false);
                    if (realHost.indexOf(':') != - 1 && ! realHost.startsWith("[") && ! realHost.endsWith("]")) {
                        // probably IPv6?
                        realHost = "[" + realHost + "]";
                    }
                    try {
                        uriList.add(new URI(protocol, null, realHost, Integer.parseInt(port), null, null, null));
                    } catch (URISyntaxException e) {
                        throw Messages.log.invalidProviderUri(e, protocol + "://" + realHost + ":" + port);
                    }
                }
            }
            return uriList;
        }

        List<URI> ret = new ArrayList<>();
        try (final ServicesQueue queue = Discovery.getContextManager().get().discover(
                EJB_SERVICE_TYPE, FilterSpec.all()
        )) {
            ret.add(queue.take());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return ret.isEmpty() ? null : ret;
    }

    private String getStringProperty(final String propertyName, final FastHashtable<String, Object> env) {
        final Object propertyValue = env.get(propertyName);
        return propertyValue == null ? null : (String) propertyValue;
    }

    ReparsedName reparse(final Name origName) throws InvalidNameException {
        final Name name = (Name) origName.clone();
        if (name.isEmpty()) {
            return new ReparsedName(null, name);
        }
        final String first = name.get(0);
        final int idx = first.indexOf(':');
        final String urlScheme;
        if (idx != -1) {
            urlScheme = first.substring(0, idx);
            final String segment = first.substring(idx+1);

            name.remove(0);
            if(segment.length()>0 || (origName.size()>1 && origName.get(1).length()>0)){
                name.add(0, segment);
            }
            return new ReparsedName(urlScheme.isEmpty() ? null : urlScheme, name);
        } else {
            return new ReparsedName(null, name);
        }
    }

    class ReparsedName {
        final String urlScheme;
        final Name name;

        ReparsedName(final String urlScheme, final Name name){
            this.urlScheme = urlScheme;
            this.name = name;
        }

        public String getUrlScheme() {
            return urlScheme;
        }

        public Name getName() {
            return name;
        }

        boolean isEmpty(){
            return urlScheme == null && name.isEmpty();
        }
    }

    private class ContextResult {
        final Context context;
        final boolean oldStyle;

        private ContextResult(Context context, boolean oldStyle) {
            this.context = context;
            this.oldStyle = oldStyle;
        }


    }
}
