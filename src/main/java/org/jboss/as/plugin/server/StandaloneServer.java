/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.plugin.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.plugin.common.ConnectionInfo;
import org.jboss.as.plugin.common.Files;
import org.jboss.as.plugin.common.Streams;
import org.jboss.as.plugin.deployment.Deployment;
import org.jboss.as.plugin.deployment.standalone.StandaloneDeployment;
import org.jboss.dmr.ModelNode;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
class StandaloneServer {

    private static final String CONFIG_PATH = "/standalone/configuration/";
    private static final ModelNode RELOAD = new ModelNode("reload");

    private final ConnectionInfo connectionInfo;
    private final File jbossHome;
    private final File modulesPath;
    private final File bundlesPath;
    private final String[] jvmArgs;
    private final String javaHome;
    private final String serverConfig;
    private final long startupTimeout;
    private volatile boolean isRunning;
    private ModelControllerClient modelControllerClient;
    private Process process;
    private ConsoleConsumer console;

    StandaloneServer(final ConnectionInfo connectionInfo, final File jbossHome, final File modulesPath, final File bundlesPath, final String[] jvmArgs, final String javaHome, final String serverConfig, final long startupTimeout) {
        this.connectionInfo = connectionInfo;
        this.jbossHome = jbossHome;
        this.modulesPath = modulesPath;
        this.bundlesPath = bundlesPath;
        this.jvmArgs = jvmArgs;
        this.javaHome = javaHome;
        this.serverConfig = serverConfig;
        this.startupTimeout = startupTimeout;
        isRunning = false;
    }

    public synchronized final void start() throws IOException, InterruptedException {
        final List<String> cmd = createLaunchCommand();
        final ProcessBuilder processBuilder = new ProcessBuilder(cmd);
        processBuilder.redirectErrorStream(true);
        process = processBuilder.start();
        console = ConsoleConsumer.start(process.getInputStream());
        long timeout = startupTimeout * 1000;
        boolean serverAvailable = false;
        long sleep = 50;
        modelControllerClient = ModelControllerClient.Factory.create(connectionInfo.getHostAddress(), connectionInfo.getPort(), connectionInfo.getCallbackHandler());
        while (timeout > 0 && !serverAvailable) {
            serverAvailable = isServerInRunningState();
            if (!serverAvailable) {
                if (processHasDied(process))
                    break;
                Thread.sleep(sleep);
                timeout -= sleep;
                sleep = Math.max(sleep / 2, 100);
            }
        }
        if (!serverAvailable) {
            destroyProcess();
            throw new IllegalStateException(String.format("Managed server was not started within [%d] s", startupTimeout));
        }
    }

    public synchronized void stop() {
        if (isServerInRunningState()) {
            try {
                final ModelNode op = new ModelNode();
                op.get(ClientConstants.OP).set("shutdown");
                try {
                    modelControllerClient.execute(op);
                } catch (IOException e) {
                    // no-op
                } finally {
                    Streams.safeClose(modelControllerClient);
                    modelControllerClient = null;
                }
                try {
                    console.awaitShutdown(5L);
                } catch (InterruptedException ignore) {
                    // no-op
                }
            } finally {
                isRunning = false;
                if (process != null) {
                    process.destroy();
                    try {
                        process.waitFor();
                    } catch (InterruptedException ignore) {
                        // no-op
                    }
                }
            }
        }
    }

    public synchronized boolean isServerInRunningState() {
        if (isRunning) {
            return isRunning;
        }
        if (modelControllerClient == null) {
            isRunning = false;
        } else {
            try {
                ModelNode op = new ModelNode();
                op.get(ClientConstants.OP_ADDR).set(new ModelNode());
                op.get(ClientConstants.OP).set("read-attribute");
                op.get(ClientConstants.NAME).set("server-state");

                ModelNode rsp = modelControllerClient.execute(op);
                isRunning = ClientConstants.SUCCESS.equals(rsp.get(ClientConstants.OUTCOME).asString())
                        && !"STARTING".equals(rsp.get(ClientConstants.RESULT).asString())
                        && !"STOPPING".equals(rsp.get(ClientConstants.RESULT).asString());
            } catch (Throwable ignore) {
                isRunning = false;
            }
        }
        return isRunning;
    }

    public synchronized void deploy(final File file, final String deploymentName) throws MojoExecutionException, MojoFailureException, IOException {
        if (isRunning) {
            switch (StandaloneDeployment.create(modelControllerClient, file, deploymentName, Deployment.Type.DEPLOY).execute()) {
                case REQUIRES_RESTART: {
                    modelControllerClient.execute(RELOAD);
                    break;
                }
                case SUCCESS:
                    break;
            }
        } else {
            throw new IllegalStateException("Cannot deploy to a server that is not running.");
        }
    }

    private List<String> createLaunchCommand() {
        final File modulesJar = new File(Files.createPath(jbossHome.getAbsolutePath(), "jboss-modules.jar"));
        if (!modulesJar.exists())
            throw new IllegalStateException("Cannot find: " + modulesJar);
        String javaExec = Files.createPath(javaHome, "bin", "java");
        if (javaHome.contains(" ")) {
            javaExec = "\"" + javaExec + "\"";
        }

        // Create the commands
        final List<String> cmd = new ArrayList<String>();
        cmd.add(javaExec);
        if (jvmArgs != null) {
            Collections.addAll(cmd, jvmArgs);
        }

        cmd.add("-Djboss.home.dir=" + jbossHome);
        cmd.add("-Dorg.jboss.boot.log.file=" + jbossHome + "/standalone/log/boot.log");
        cmd.add("-Dlogging.configuration=file:" + jbossHome + CONFIG_PATH + "logging.properties");
        cmd.add("-Djboss.modules.dir=" + modulesPath.getAbsolutePath());
        cmd.add("-Djboss.bundles.dir=" + bundlesPath.getAbsolutePath());
        cmd.add("-jar");
        cmd.add(modulesJar.getAbsolutePath());
        cmd.add("-mp");
        cmd.add(modulesPath.getAbsolutePath());
        cmd.add("-jaxpmodule");
        cmd.add("javax.xml.jaxp-provider");
        cmd.add("org.jboss.as.standalone");
        if (serverConfig != null) {
            cmd.add("-server-config");
            cmd.add(serverConfig);
        }
        return cmd;
    }


    private int destroyProcess() {
        if (process == null)
            return 0;
        process.destroy();
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean processHasDied(final Process process) {
        try {
            process.exitValue();
            return true;
        } catch (IllegalThreadStateException e) {
            // good
            return false;
        }
    }


    /**
     * Runnable that consumes the output of the process. If nothing consumes the output the AS will hang on some
     * platforms
     *
     * @author Stuart Douglas
     */
    private static class ConsoleConsumer implements Runnable {

        static ConsoleConsumer start(final InputStream stream) {
            final ConsoleConsumer result = new ConsoleConsumer(stream);
            final Thread t = new Thread(result);
            t.setName("AS7-Console");
            t.start();
            return result;
        }

        private final BufferedReader reader;
        private final CountDownLatch shutdownLatch;

        private ConsoleConsumer(final InputStream stream) {
            reader = new BufferedReader(new InputStreamReader(stream));
            shutdownLatch = new CountDownLatch(1);
        }

        @Override
        public void run() {

            String line;
            try {
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                    if (line.contains("JBAS015950"))
                        shutdownLatch.countDown();
                }
            } catch (IOException ignore) {
            }
        }

        void awaitShutdown(final long seconds) throws InterruptedException {
            shutdownLatch.await(seconds, TimeUnit.SECONDS);
        }

    }
}
