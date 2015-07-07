/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sshd.client.channel;

import java.io.IOException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.channel.PtyMode;
import org.apache.sshd.common.channel.SttySupport;
import org.apache.sshd.common.util.GenericUtils;
import org.apache.sshd.common.util.OsUtils;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;

/**
 * <P>Serves as the base channel session for executing remote commands - including
 * a full shell. <B>Note:</B> all the configuration changes via the various
 * {@code setXXX} methods must be made <U>before</U> the channel is actually
 * open. If they are invoked afterwards then they have no effect (silently
 * ignored).</P>
 * <P>A typical code snippet would be:</P>
 * <CODE><PRE>
 *      client = SshClient.setUpDefaultClient();
 *      client.start();
 *
 *      try(ClientSession s = client.connect(getCurrentTestName(), "localhost", port).verify(7L, TimeUnit.SECONDS).getSession()) {
 *          s.addPasswordIdentity(getCurrentTestName());
 *          s.auth().verify(5L, TimeUnit.SECONDS);
 *
 *          try(ChannelExec shell = s.createExecChannel("my super duper command")) {
 *              shell.setEnv("var1", "val1");
 *              shell.setEnv("var2", "val2");
 *              ...etc...
 *              shell.setPtyType(...);
 *              shell.setPtyLines(...);
 *              ...etc...
 *
 *              shell.open().verify(5L, TimeUnit.SECONDS);
 *              shell.waitFor(ClientChannel.CLOSED, TimeUnit.SECONDS.toMillis(17L));    // can use zero for infinite wait
 *               
 *              Integer status = shell.getExitStatus();
 *              if (status.intValue() != 0) {
 *                  ...error...
 *              }
 *          }
 *      } finally {
 *          client.stop();
 *      }
 * </PRE></CODE>
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public class PtyCapableChannelSession extends ChannelSession {
    public static final int DEFAULT_COLUMNS_COUNT = 80;
    public static final int DEFAULT_ROWS_COUNT = 24;
    public static final int DEFAULT_WIDTH = 640;
    public static final int DEFAULT_HEIGHT = 480;
    public static final Map<PtyMode, Integer> DEFAULT_PTY_MODES =
            Collections.unmodifiableMap(new EnumMap<PtyMode, Integer>(PtyMode.class) {
                private static final long serialVersionUID = 1L;    // we're not serializing it

                {
                    put(PtyMode.ISIG, Integer.valueOf(1));
                    put(PtyMode.ICANON, Integer.valueOf(1));
                    put(PtyMode.ECHO, Integer.valueOf(1));
                    put(PtyMode.ECHOE, Integer.valueOf(1));
                    put(PtyMode.ECHOK, Integer.valueOf(1));
                    put(PtyMode.ECHONL, Integer.valueOf(0));
                    put(PtyMode.NOFLSH, Integer.valueOf(0));
                }
            });

    private boolean agentForwarding;
    private boolean usePty;
    private String ptyType;
    private int ptyColumns = DEFAULT_COLUMNS_COUNT;
    private int ptyLines = DEFAULT_ROWS_COUNT;
    private int ptyWidth = DEFAULT_WIDTH;
    private int ptyHeight = DEFAULT_HEIGHT;
    private Map<PtyMode, Integer> ptyModes = new EnumMap<PtyMode, Integer>(PtyMode.class);
    private final Map<String, String> env = new LinkedHashMap<String, String>();

    public PtyCapableChannelSession(boolean usePty) {
        this.usePty = usePty;
        ptyType = System.getenv("TERM");
        if (GenericUtils.isEmpty(ptyType)) {
            ptyType = "dummy";
        }

        ptyModes.putAll(DEFAULT_PTY_MODES);
    }

    public void setupSensibleDefaultPty() {
        try {
            if (OsUtils.isUNIX()) {
                ptyModes = SttySupport.getUnixPtyModes();
                ptyColumns = SttySupport.getTerminalWidth();
                ptyLines = SttySupport.getTerminalHeight();
            } else {
                ptyType = "windows";
            }
        } catch (Throwable t) {
            // Ignore exceptions
        }
    }

    public boolean isAgentForwarding() {
        return agentForwarding;
    }

    public void setAgentForwarding(boolean agentForwarding) {
        this.agentForwarding = agentForwarding;
    }

    public boolean isUsePty() {
        return usePty;
    }

    public void setUsePty(boolean usePty) {
        this.usePty = usePty;
    }

    public String getPtyType() {
        return ptyType;
    }

    public void setPtyType(String ptyType) {
        this.ptyType = ptyType;
    }

    public int getPtyColumns() {
        return ptyColumns;
    }

    public void setPtyColumns(int ptyColumns) {
        this.ptyColumns = ptyColumns;
    }

    public int getPtyLines() {
        return ptyLines;
    }

    public void setPtyLines(int ptyLines) {
        this.ptyLines = ptyLines;
    }

    public int getPtyWidth() {
        return ptyWidth;
    }

    public void setPtyWidth(int ptyWidth) {
        this.ptyWidth = ptyWidth;
    }

    public int getPtyHeight() {
        return ptyHeight;
    }

    public void setPtyHeight(int ptyHeight) {
        this.ptyHeight = ptyHeight;
    }

    public Map<PtyMode, Integer> getPtyModes() {
        return ptyModes;
    }

    public void setPtyModes(Map<PtyMode, Integer> ptyModes) {
        this.ptyModes = (ptyModes == null) ? Collections.<PtyMode, Integer>emptyMap() : ptyModes;
    }

    public void setEnv(String key, String value) {
        env.put(key, value);
    }

    protected void doOpenPty() throws IOException {
        Buffer buffer;

        if (agentForwarding) {
            if (log.isDebugEnabled()) {
                log.debug("Send agent forwarding request - recipient={}", Integer.valueOf(recipient));
            }
            buffer = session.createBuffer(SshConstants.SSH_MSG_CHANNEL_REQUEST);
            buffer.putInt(recipient);
            buffer.putString("auth-agent-req@openssh.com");
            buffer.putBoolean(false);
            writePacket(buffer);
        }

        if (usePty) {
            if (log.isDebugEnabled()) {
                log.debug("Send SSH_MSG_CHANNEL_REQUEST pty-req: type={}, cols={}, lines={}, height={}, width={}, modes={}",
                          ptyType, Integer.valueOf(ptyColumns), Integer.valueOf(ptyLines),
                          Integer.valueOf(ptyHeight), Integer.valueOf(ptyWidth), ptyModes);
            }

            buffer = session.createBuffer(SshConstants.SSH_MSG_CHANNEL_REQUEST);
            buffer.putInt(recipient);
            buffer.putString("pty-req");
            buffer.putBoolean(false);
            buffer.putString(ptyType);
            buffer.putInt(ptyColumns);
            buffer.putInt(ptyLines);
            buffer.putInt(ptyHeight);
            buffer.putInt(ptyWidth);

            Buffer modes = new ByteArrayBuffer();
            for (Map.Entry<PtyMode,? extends Number> modeEntry : ptyModes.entrySet()) {
                PtyMode mode = modeEntry.getKey();
                Number value = modeEntry.getValue();
                modes.putByte((byte) mode.toInt());
                modes.putInt(value.longValue());
            }
            modes.putByte((byte) 0);
            buffer.putBytes(modes.getCompactData());
            writePacket(buffer);
        }

        if (GenericUtils.size(env) > 0) {
            log.debug("Send SSH_MSG_CHANNEL_REQUEST env: {}", env);
            for (Map.Entry<String, String> entry : env.entrySet()) {
                buffer = session.createBuffer(SshConstants.SSH_MSG_CHANNEL_REQUEST);
                buffer.putInt(recipient);
                buffer.putString("env");
                buffer.putBoolean(false);
                buffer.putString(entry.getKey());
                buffer.putString(entry.getValue());
                writePacket(buffer);
            }
        }
    }
}
