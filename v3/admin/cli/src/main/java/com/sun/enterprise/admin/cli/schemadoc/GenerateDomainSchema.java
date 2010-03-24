/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2010 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.enterprise.admin.cli.schemadoc;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import com.sun.enterprise.config.serverbeans.Domain;
import org.glassfish.api.Param;
import org.glassfish.api.admin.AdminCommand;
import org.glassfish.api.admin.AdminCommandContext;
import org.jvnet.hk2.annotations.Inject;
import org.jvnet.hk2.annotations.Scoped;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.component.Habitat;
import org.jvnet.hk2.component.PerLookup;
import org.objectweb.asm.ClassReader;

@Service(name = "generate-domain-schema")
@Scoped(PerLookup.class)
public class GenerateDomainSchema implements AdminCommand {
    @Inject
    private Domain domain;
    @Inject
    private Habitat habitat;
    @Param(name = "format", defaultValue = "html", optional = true)
    private String format;
    File docDir;
    private Map<String, ClassDef> classDefs = new HashMap<String, ClassDef>();
    @Param(name = "showSubclasses", defaultValue = "false", optional = true)
    private Boolean showSubclasses;
    @Param(name = "showDeprecated", defaultValue = "false", optional = true)
    private Boolean showDeprecated;

    public void execute(AdminCommandContext context) {
        try {
            URI uri = new URI(System.getProperty("com.sun.aas.instanceRootURI"));
            docDir = new File(new File(uri), "config");
            findClasses(classDefs, locateJarFiles(System.getProperty("com.sun.aas.installRoot") + "/modules"));

            getFormat().output(new Context(classDefs, docDir, Boolean.valueOf(showDeprecated),
                Boolean.valueOf(showSubclasses), Domain.class.getName()));
            context.getActionReport().setMessage("Finished generating " + format + " documentation in " + docDir);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private SchemaOutputFormat getFormat() {
        return habitat.getComponent(SchemaOutputFormat.class, format);
    }

    private List<JarFile> locateJarFiles(String modulesDir) throws IOException {
        List<JarFile> result = new ArrayList<JarFile>();
        final File[] files = new File(modulesDir).listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.endsWith(".jar");
            }
        });
        for (File f : files) {
            result.add(new JarFile(f));
        }
        return result;
    }

    private void findClasses(Map<String, ClassDef> classDefs, List<JarFile> jarFiles) throws IOException {
        for (JarFile jf : jarFiles) {
            for (Enumeration<JarEntry> entries = jf.entries(); entries.hasMoreElements();) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    ClassDef def = parse(jf.getInputStream(entry));
                    if (def != null) {
                        classDefs.put(def.getDef(), def);
                        for (String intf : def.getInterfaces()) {
                            final ClassDef parent = classDefs.get(intf);
                            if (parent != null) {
                                parent.addSubclass(def);
                            }
                        }
                    }
                }
            }
        }
        if (showSubclasses) {
            for (ClassDef def : classDefs.values()) {
                for (String anInterface : def.getInterfaces()) {
                    final ClassDef parent = classDefs.get(anInterface);
                    if (parent != null) {
                        parent.addSubclass(def);
                    }
                }
            }
        }
    }

    private ClassDef parse(InputStream is) throws IOException {
        DocClassVisitor visitor = new DocClassVisitor(Boolean.valueOf(showDeprecated));
        new ClassReader(is).accept(visitor, 0);
        return visitor.isConfigured() ? visitor.getClassDef() : null;
    }

    public static String toClassName(String value) {
        int start = value.startsWith("()") ? 2 : 0;
        start = value.substring(start).startsWith("L") ? start + 1 : start;
        final int end = value.endsWith(";") ? value.length() - 1 : value.length();
        return value
            .substring(start, end)
            .replace('/', '.');
    }
}