/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2010 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
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

package com.sun.enterprise.security.cli;

import java.util.List;
import java.util.Properties;

import org.glassfish.api.admin.AdminCommand;
import org.glassfish.api.admin.AdminCommandContext;
import org.glassfish.api.I18n;
import org.glassfish.api.Param;
import org.glassfish.api.ActionReport;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.annotations.Scoped;
import org.jvnet.hk2.annotations.Inject;
import org.jvnet.hk2.component.PerLookup;
import org.jvnet.hk2.config.ConfigSupport;
import org.jvnet.hk2.config.SingleConfigCode;
import org.jvnet.hk2.config.TransactionFailure;
import org.jvnet.hk2.config.types.Property;
import com.sun.enterprise.config.serverbeans.SecurityService;
import com.sun.enterprise.config.serverbeans.AuthRealm;
import com.sun.enterprise.config.serverbeans.Config;
import com.sun.enterprise.config.serverbeans.Configs;
import com.sun.enterprise.config.serverbeans.Domain;
import com.sun.enterprise.config.serverbeans.Server;
import com.sun.enterprise.security.SecurityConfigListener;
import com.sun.enterprise.util.LocalStringManagerImpl;

import com.sun.enterprise.util.SystemPropertyConstants;
import java.beans.PropertyVetoException;
import org.glassfish.api.admin.ExecuteOn;
import org.glassfish.api.admin.RuntimeType;
import org.glassfish.api.admin.ServerEnvironment;
import org.glassfish.config.support.CommandTarget;
import org.glassfish.config.support.PropertyResolver;
import org.glassfish.config.support.TargetType;
import org.glassfish.internal.api.RelativePathResolver;


/**
 * CLI command to create JACC Provider
 *
 * Usage: create-auth-realm --classname realm_class [--terse=false] 
 *        [--interactive=true] [--host localhost] [--port 4848|4849] 
 *        [--secure | -s] [--user admin_user] [--passwordfile file_name] 
 *        [--property (name=value)[:name=value]*] 
 *        [--echo=false] [--target target(Default server)] auth_realm_name
 *  
 * domain.xml element example
 * <auth-realm name="file" 
 *   classname="com.sun.enterprise.security.auth.realm.file.FileRealm">
 *   <property name="file" value="${com.sun.aas.instanceRoot}/config/keyfile"/>
 *   <property name="jaas-context" value="fileRealm"/>
 * </auth-realm>
 *       Or
 * <auth-realm name="certificate" 
 *   classname="com.sun.enterprise.security.auth.realm.certificate.CertificateRealm">
 * </auth-realm>
 *
 * @author Nandini Ektare
 */

@Service(name="create-auth-realm")
@Scoped(PerLookup.class)
@I18n("create.auth.realm")
@ExecuteOn({RuntimeType.DAS, RuntimeType.INSTANCE})
@TargetType({CommandTarget.DAS,CommandTarget.STANDALONE_INSTANCE,CommandTarget.CLUSTER, CommandTarget.CONFIG})
public class CreateAuthRealm implements AdminCommand {
    
    final private static LocalStringManagerImpl localStrings = 
        new LocalStringManagerImpl(CreateAuthRealm.class);    

    @Param(name="classname")
    private String className;

    @Param(name="authrealmname", primary=true)
    private String authRealmName;
    
    @Param(optional=true, name="property", separator=':')
    private Properties properties;
    
    @Param(name = "target", optional = true, defaultValue =
    SystemPropertyConstants.DEFAULT_SERVER_INSTANCE_NAME)
    private String target;

    @Inject(name = ServerEnvironment.DEFAULT_INSTANCE_NAME)
    private Config config;

    @Inject
    private Configs configs;

    @Inject
    private Domain domain;
    @Inject
    private SecurityConfigListener securityListener;

    /**
     * Executes the command with the command parameters passed as Properties
     * where the keys are the paramter names and the values the parameter values
     *
     * @param context information
     */
    public void execute(AdminCommandContext context) {
        final ActionReport report = context.getActionReport();

        Config tmp = null;
        try {
            tmp = configs.getConfigByName(target);
        } catch (Exception ex) {
        }

        if (tmp != null) {
            config = tmp;
        }
        if (tmp == null) {
            Server targetServer = domain.getServerNamed(target);
            if (targetServer != null) {
                config = domain.getConfigNamed(targetServer.getConfigRef());
            }
            com.sun.enterprise.config.serverbeans.Cluster cluster = domain.getClusterNamed(target);
            if (cluster != null) {
                config = domain.getConfigNamed(cluster.getConfigRef());
            }
        }
        SecurityService securityService = config.getSecurityService();
        
        // check if there exists an auth realm byt he specified name
        // if so return failure.
        List<AuthRealm> authrealms = securityService.getAuthRealm();
        for (AuthRealm authrealm : authrealms) {
            if (authrealm.getName().equals(authRealmName)) {
                report.setMessage(localStrings.getLocalString(
                    "create.auth.realm.duplicatefound", 
                    "Authrealm named {0} exists. Cannot add duplicate AuthRealm.", 
                    authRealmName));
                report.setActionExitCode(ActionReport.ExitCode.FAILURE);
                return;
            }
        }
        
        // No duplicate auth realms found. So add one.
        try {
            ConfigSupport.apply(new SingleConfigCode<SecurityService>() {

                public Object run(SecurityService param) 
                throws PropertyVetoException, TransactionFailure {
                AuthRealm newAuthRealm = param.createChild(AuthRealm.class);
                    populateAuthRealmElement(newAuthRealm);
                    param.getAuthRealm().add(newAuthRealm);
                    //In case of cluster instances, this is required to
                    //avoid issues with the listener's callback method
                    SecurityConfigListener.authRealmCreated(config, newAuthRealm);
                    return newAuthRealm;
                    
                }
            }, securityService);
        } catch(TransactionFailure e) {
            report.setMessage(localStrings.getLocalString("create.auth.realm.fail", 
                    "Creation of Authrealm {0} failed", authRealmName) +
                              "  " + e.getLocalizedMessage() );
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            report.setFailureCause(e);
            return;
        }

        report.setActionExitCode(ActionReport.ExitCode.SUCCESS);
    }       
    
    private void populateAuthRealmElement(AuthRealm newAuthRealm) 
    throws PropertyVetoException, TransactionFailure {
        newAuthRealm.setName(authRealmName);
        newAuthRealm.setClassname(className);
        if (properties != null) {
            for (Object propname: properties.keySet()) {
                Property newprop = newAuthRealm.createChild(Property.class);
                newprop.setName((String) propname);
                String propValue = properties.getProperty((String) propname);
                if (propValue != null && propValue.contains("$")) {
                    propValue = RelativePathResolver.resolvePath(propValue);
                }
                newprop.setValue(propValue);
                newAuthRealm.getProperty().add(newprop);    
            }
        }
    }    
}
