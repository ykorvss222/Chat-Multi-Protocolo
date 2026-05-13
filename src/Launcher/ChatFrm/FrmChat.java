package Launcher.ChatFrm;

import Launcher.ChatFrm.FrmLobby.InfoSala;
import Launcher.ChatUI.PerfilManager;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;


/**
 *
 * @author yakor
 */
public class FrmChat extends javax.swing.JFrame {
    
    private static final java.util.logging.Logger logger = java.util.logging.Logger.getLogger(FrmChat.class.getName());
    
    private PerfilManager perfil;
    private InfoSala sala;
    private IClienteRed clienteRed;
    private javax.swing.JPopupMenu menuEmojis;
    private String ultimoMensajeEnviado = "";

    // --- INTERFACES DE RED DE ChatCliente ---
    interface IClienteRed {
        void conectar(String ip, int port) throws IOException;
        void enviar(String msj) throws IOException;
        String recibir() throws IOException;
        void cerrar();
    }

    static class ClienteTCP implements IClienteRed {
        private Socket socket;
        private BufferedReader in;
        private PrintWriter out;

        public void conectar(String ip, int port) throws IOException {
            socket = new Socket(ip, port);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        }
        public void enviar(String msj) throws IOException { out.println(msj); }
        public String recibir() throws IOException { return in.readLine(); }
        public void cerrar() { try { socket.close(); } catch(Exception e){} }
    }

    static class ClienteUDP implements IClienteRed {
        private DatagramSocket socket;
        private InetAddress serverAddress;
        private int serverPort;

        public void conectar(String ip, int port) throws IOException {
            socket = new DatagramSocket();
            serverAddress = InetAddress.getByName(ip);
            serverPort = port;
        }
        public void enviar(String msj) throws IOException {
            byte[] data = msj.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(data, data.length, serverAddress, serverPort));
        }
        public String recibir() throws IOException {
            byte[] buffer = new byte[65507];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            return new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
        }
        public void cerrar() { socket.close(); }
    }

    /**
     * Creates new form FrmChat
     */
    public FrmChat(InfoSala sala, PerfilManager perfil) {
        initComponents();
        
        this.sala = sala;
        this.perfil = perfil;
        
        // Configuración visual
        this.setTitle(sala.nombre);
        
        javax.swing.border.TitledBorder border = (javax.swing.border.TitledBorder) jPanel3.getBorder();
        border.setTitle("Enviar en " + sala.nombre + ":");
        jPanel3.repaint();
        
        // Configuramos para usar HTML con CSS
        jTextPane1.setContentType("text/html");
        jTextPane1.setEditable(false);
        String estilosCSS = "<style>body { font-family: 'Segoe UI', 'Segoe UI Emoji', sans-serif; font-size: 11px; margin: 5px; } p { margin-top: 2px; margin-bottom: 2px; }</style>";
        jTextPane1.setText("<html><head>" + estilosCSS + "</head><body></body></html>");
        
        iniciarConexion();
        
        // cierra el socket
        this.setDefaultCloseOperation(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);
        this.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                desconectarChat(); 
            }
        });
    }

    private void iniciarConexion() {
        // shift + enter para saltar de línea
        txtMensaje.addKeyListener(new java.awt.event.KeyAdapter() {
            public void keyPressed(java.awt.event.KeyEvent evt) {
                if (evt.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER) {
                    if (evt.isShiftDown()) {
                    } else {
                        evt.consume(); 
                        btnEnviarActionPerformed(null);
                    }
                } else if (evt.getKeyCode() == java.awt.event.KeyEvent.VK_UP) {
                    // flecha arriba para obtener mensaje
                    if (!ultimoMensajeEnviado.isEmpty()) {
                        txtMensaje.setText(ultimoMensajeEnviado);
                    }
                }
            }
        });
        
        try {
            // Decidimos si usamos TCP o UDP
            clienteRed = sala.protocolo.equals("TCP") ? new ClienteTCP() : new ClienteUDP();
            clienteRed.conectar(sala.ip, sala.puerto);
            
            // Comando para registrarse
            clienteRed.enviar("REGISTRO:" + perfil.getUsername());
            
            // Hilo en segundo plano para escuchar los mensajes del servidor
            Thread hiloReceptor = new Thread(() -> {
                try {
                    while (true) {
                        String msj = clienteRed.recibir();
                        if (msj == null) break;
                        procesarMensajeEntrante(msj);
                    }
                } catch (IOException e) {
                    agregarHTMLAlHistorial("<p style='color:red;'><b>❌ Desconectado del servidor.</b></p>");
                    
                    txtMensaje.setEnabled(false);
                    btnEnviar.setEnabled(false);
                    btnInsertar.setEnabled(false);
                }
            });
            hiloReceptor.start();
            
        } catch (Exception e) {
            agregarHTMLAlHistorial("<p style='color:red;'><b>Error al conectar: " + e.getMessage() + "</b></p>");
        }
    }
    
    private void procesarMensajeEntrante(String msj) {
        String msjLimpio = msj.replaceAll("\u001B\\[[;\\d]*m", "");
        
        // --- EXTRACCIÓN DE IMÁGENES DEL TEXTO ---
        if (msjLimpio.contains("[IMG_BASE64]") && msjLimpio.contains("[/IMG_BASE64]")) {
            int inicio = msjLimpio.indexOf("[IMG_BASE64]");
            int fin = msjLimpio.indexOf("[/IMG_BASE64]");
            
            // obtenemos el código base64
            String base64 = msjLimpio.substring(inicio + 12, fin);
            String etiquetaHtml = procesarImagenHTML(base64);
            
            // Reemplazamos 
            msjLimpio = msjLimpio.substring(0, inicio) + etiquetaHtml + msjLimpio.substring(fin + 13);
        }
        
        if (msjLimpio.equals("REGISTRO_EXITOSO")) {
            agregarHTMLAlHistorial("<p style='color:green;'><b>&#10004; ¡Conectado a la sala exitosamente!</b></p>");
            agregarHTMLAlHistorial("<p style='color:gray;'><b>Comandos: '/msg usuario texto' (Privado), '/userlist' (Usuarios), '/exit' (Salir)</b></p><hr>");
            return; 
        } else if (msjLimpio.equals("USUARIO_REPETIDO")) {
            agregarHTMLAlHistorial("<p style='color:red;'><b>❌ Error: Ese nombre de usuario ya está conectado a esta sala.</b></p>");
            return;
        } else if (msjLimpio.equals("SERVIDOR_LLENO")) {
            agregarHTMLAlHistorial("<p style='color:red;'><b>❌ Error: La sala está llena. No puedes entrar.</b></p>");
            return;
        } else if (msjLimpio.equals("NOMBRE_INVALIDO")) {
            agregarHTMLAlHistorial("<p style='color:red;'><b>❌ Error: Tu nombre de usuario es inválido.</b></p>");
            return;
        }
        
        // --- LÓGICA PARA ENTRADAS Y SALIDAS ---
        if (msjLimpio.contains("se ha unido a la sala") || msjLimpio.contains("ha salido de la sala")) {
            
            int finFecha = msjLimpio.indexOf("]");
            String fecha = msjLimpio.substring(0, finFecha + 1);
            String accion = msjLimpio.substring(finFecha + 1).trim();
            
            String html = "<table width='100%' cellpadding='0' cellspacing='0'><tr>" +
                          "<td width='25%' style='color:#999999; font-size:10px; text-align:left;'>" + fecha + "</td>" +
                          "<td width='75%' style='color:#999999; font-size:10px; font-style:italic; text-align:center;'>" + accion + "</td>" +
                          "</tr></table>";
            
            agregarHTMLAlHistorial(html);
            return;
        }
        
        // --- LÓGICA PARA LA LISTA DE USUARIOS ---
        if (msjLimpio.startsWith("--- Usuarios Conectados")) {
            agregarHTMLAlHistorial("<br><div style='background-color: #e6f2ff; padding: 5px; border-left: 3px solid #0066cc;'><b style='color:#0066cc;'>👥 " + msjLimpio.replace("--- ", "").replace(" ---", "") + "</b><br>");
            return;
        }
        if (msjLimpio.startsWith("- ") && !msjLimpio.contains("ha salido") && !msjLimpio.contains("se ha unido")) {
            String nombreU = msjLimpio.replace("- ", "").replace(" (Tú)", "").trim();
            agregarHTMLAlHistorial("👤 <b>" + msjLimpio + "</b> <span style='color:gray; font-size:9px;'>(Escribe: <i>/msg " + nombreU + "</i>)</span><br>");
            return;
        }
        if (msjLimpio.startsWith("-----------------------------")) {
            agregarHTMLAlHistorial("</div><br>");
            return;
        }
        
        // ---LÓGICA PARA MENSAJES PRIVADOS ---
        if (msjLimpio.contains("(Privado de ")) {
            // Mensaje ENTRANTE
            agregarHTMLAlHistorial("<p style='color:#800080;'><b>---></b> " + msjLimpio + "</p>");
            return;
        } else if (msjLimpio.contains("(Privado para ")) {
            // Mensaje SALIENTE
            agregarHTMLAlHistorial("<p style='color:#800080;'>" + msjLimpio + "</p>");
            return;
        }
        
        // Identificación de colores de chat normal
        String color = "black";
        if (msjLimpio.contains("Tú:")) color = "blue";
        else if (msjLimpio.contains("(Privado")) color = "purple";
        else if (msjLimpio.startsWith("[")) color = "#333333";
        else if (msjLimpio.startsWith("---")) color = "gray";

        agregarHTMLAlHistorial("<p style='color:" + color + ";'>" + msjLimpio + "</p>");
    }

    private void agregarHTMLAlHistorial(String htmlInyectar) {
        try {
            javax.swing.text.html.HTMLDocument doc = (javax.swing.text.html.HTMLDocument) jTextPane1.getDocument();
            javax.swing.text.html.HTMLEditorKit editorKit = (javax.swing.text.html.HTMLEditorKit) jTextPane1.getEditorKit();
            
            editorKit.insertHTML(doc, doc.getLength(), htmlInyectar, 0, 0, null);
            
            // Autoscroll hacia abajo
            jTextPane1.setCaretPosition(doc.getLength());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        panelChat = new javax.swing.JPanel();
        jPanel1 = new javax.swing.JPanel();
        btnEmoji = new javax.swing.JButton();
        btnInsertar = new javax.swing.JButton();
        btnEnviar = new javax.swing.JButton();
        jScrollPane2 = new javax.swing.JScrollPane();
        txtMensaje = new javax.swing.JTextArea();
        btnToggleBold = new javax.swing.JButton();
        btnToggleItalic = new javax.swing.JButton();
        btnToggleUnderlined = new javax.swing.JButton();
        jPanel3 = new javax.swing.JPanel();
        jScrollPane1 = new javax.swing.JScrollPane();
        jTextPane1 = new javax.swing.JTextPane();
        btnSalir = new javax.swing.JButton();

        setTitle("[Nombre de la sala]");
        setBackground(new java.awt.Color(232, 240, 254));

        panelChat.setBorder(javax.swing.BorderFactory.createEtchedBorder());
        panelChat.setPreferredSize(new java.awt.Dimension(800, 700));

        jPanel1.setBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(0, 0, 0)));

        btnEmoji.setIcon(new javax.swing.ImageIcon(getClass().getResource("/Launcher.Assets/add_reaction_20dp_666666_FILL0_wght400_GRAD0_opsz20.png"))); // NOI18N
        btnEmoji.addActionListener(this::btnEmojiActionPerformed);

        btnInsertar.setIcon(new javax.swing.ImageIcon(getClass().getResource("/Launcher.Assets/image_search_32dp_666666_FILL0_wght400_GRAD0_opsz40.png"))); // NOI18N
        btnInsertar.setToolTipText("(Solo TCP)");
        btnInsertar.addActionListener(this::btnInsertarActionPerformed);

        btnEnviar.setIcon(new javax.swing.ImageIcon(getClass().getResource("/Launcher.Assets/send_64dp_666666_FILL0_wght400_GRAD0_opsz48.png"))); // NOI18N
        btnEnviar.addActionListener(this::btnEnviarActionPerformed);

        txtMensaje.setColumns(20);
        txtMensaje.setLineWrap(true);
        txtMensaje.setRows(5);
        jScrollPane2.setViewportView(txtMensaje);

        btnToggleBold.setIcon(new javax.swing.ImageIcon(getClass().getResource("/Launcher.Assets/format_bold_20dp_666666_FILL0_wght400_GRAD0_opsz20.png"))); // NOI18N
        btnToggleBold.setAlignmentY(0.0F);
        btnToggleBold.addActionListener(this::btnToggleBoldActionPerformed);

        btnToggleItalic.setIcon(new javax.swing.ImageIcon(getClass().getResource("/Launcher.Assets/format_italic_20dp_666666_FILL0_wght400_GRAD0_opsz20.png"))); // NOI18N
        btnToggleItalic.setAlignmentY(0.0F);
        btnToggleItalic.addActionListener(this::btnToggleItalicActionPerformed);

        btnToggleUnderlined.setIcon(new javax.swing.ImageIcon(getClass().getResource("/Launcher.Assets/format_underlined_20dp_666666_FILL0_wght400_GRAD0_opsz20.png"))); // NOI18N
        btnToggleUnderlined.setAlignmentY(0.0F);
        btnToggleUnderlined.addActionListener(this::btnToggleUnderlinedActionPerformed);

        javax.swing.GroupLayout jPanel1Layout = new javax.swing.GroupLayout(jPanel1);
        jPanel1.setLayout(jPanel1Layout);
        jPanel1Layout.setHorizontalGroup(
            jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel1Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel1Layout.createSequentialGroup()
                        .addComponent(btnToggleBold)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnToggleItalic)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnToggleUnderlined)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(btnEmoji)
                        .addGap(0, 0, Short.MAX_VALUE))
                    .addGroup(jPanel1Layout.createSequentialGroup()
                        .addComponent(jScrollPane2, javax.swing.GroupLayout.DEFAULT_SIZE, 664, Short.MAX_VALUE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                            .addComponent(btnEnviar, javax.swing.GroupLayout.DEFAULT_SIZE, 100, Short.MAX_VALUE)
                            .addComponent(btnInsertar, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))))
                .addContainerGap())
        );
        jPanel1Layout.setVerticalGroup(
            jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel1Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel1Layout.createSequentialGroup()
                        .addComponent(btnEmoji)
                        .addGap(0, 0, Short.MAX_VALUE))
                    .addComponent(btnToggleBold, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(btnToggleItalic, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(btnToggleUnderlined, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jScrollPane2, javax.swing.GroupLayout.PREFERRED_SIZE, 157, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addGroup(jPanel1Layout.createSequentialGroup()
                        .addComponent(btnEnviar, javax.swing.GroupLayout.PREFERRED_SIZE, 100, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnInsertar, javax.swing.GroupLayout.PREFERRED_SIZE, 45, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addGap(12, 12, 12))
        );

        jPanel3.setBorder(javax.swing.BorderFactory.createTitledBorder(null, "Enviar en [Nombre de la sala]:", javax.swing.border.TitledBorder.LEFT, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("sansserif", 1, 18))); // NOI18N

        jScrollPane1.setViewportView(jTextPane1);

        javax.swing.GroupLayout jPanel3Layout = new javax.swing.GroupLayout(jPanel3);
        jPanel3.setLayout(jPanel3Layout);
        jPanel3Layout.setHorizontalGroup(
            jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(jScrollPane1)
        );
        jPanel3Layout.setVerticalGroup(
            jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(jScrollPane1, javax.swing.GroupLayout.DEFAULT_SIZE, 400, Short.MAX_VALUE)
        );

        btnSalir.setText("Abandonar sala...");
        btnSalir.addActionListener(this::btnSalirActionPerformed);

        javax.swing.GroupLayout panelChatLayout = new javax.swing.GroupLayout(panelChat);
        panelChat.setLayout(panelChatLayout);
        panelChatLayout.setHorizontalGroup(
            panelChatLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(panelChatLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(panelChatLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jPanel3, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(jPanel1, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, panelChatLayout.createSequentialGroup()
                        .addGap(0, 0, Short.MAX_VALUE)
                        .addComponent(btnSalir, javax.swing.GroupLayout.PREFERRED_SIZE, 136, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addContainerGap())
        );
        panelChatLayout.setVerticalGroup(
            panelChatLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, panelChatLayout.createSequentialGroup()
                .addGap(14, 14, 14)
                .addComponent(btnSalir)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jPanel3, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jPanel1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap())
        );

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(panelChat, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
                .addGap(0, 0, Short.MAX_VALUE)
                .addComponent(panelChat, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
        );

        pack();
        setLocationRelativeTo(null);
    }// </editor-fold>//GEN-END:initComponents

    private void btnEnviarActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnEnviarActionPerformed
        // TODO add your handling code here:
        String texto = txtMensaje.getText().trim(); 
        if (texto.isEmpty() || clienteRed == null) return;

        // Verificamos si el usuario mandó un comando
        if (texto.equalsIgnoreCase("exit") || texto.equalsIgnoreCase("/exit")) {
            btnSalirActionPerformed(null);
            return;
        } 
        else if (texto.equalsIgnoreCase("/userlist")) {
            try { clienteRed.enviar("COMANDO:USERLIST"); } catch (Exception e) {}
        } 
        else if (texto.startsWith("/msg ")) {
            String[] partes = texto.split(" ", 3);
            if (partes.length == 3) {
                try { clienteRed.enviar("PRIVADO:" + partes[1] + ":" + partes[2]); } catch (Exception e) {}
            } else {
                agregarHTMLAlHistorial("<p style='color:red;'>Formato incorrecto. Usa: /msg usuario mensaje</p>");
            }
        } 
        // Si es un mensaje normal se aplica markdown
        else {
            String textoFormateado = texto;
            
            // Reemplaza **texto** por <b>texto</b>
            textoFormateado = textoFormateado.replaceAll("\\*\\*(.*?)\\*\\*", "<b>$1</b>");
            // Reemplaza *texto* por <i>texto</i>
            textoFormateado = textoFormateado.replaceAll("\\*(.*?)\\*", "<i>$1</i>");
            // Reemplaza __texto__ por <u>texto</u>
            textoFormateado = textoFormateado.replaceAll("__(.*?)__", "<u>$1</u>");

            try {
                clienteRed.enviar("MENSAJE:" + textoFormateado);
            } catch (Exception e) {
                agregarHTMLAlHistorial("<p style='color:red;'>Error al enviar el mensaje.</p>");
            }
        }
        
        ultimoMensajeEnviado = texto;
        
        txtMensaje.setText(""); 
        txtMensaje.requestFocus();
    }//GEN-LAST:event_btnEnviarActionPerformed

    private void btnSalirActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSalirActionPerformed
        // TODO add your handling code here:
        desconectarChat();
    }//GEN-LAST:event_btnSalirActionPerformed

    private void btnInsertarActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnInsertarActionPerformed
        // TODO add your handling code here:
        if (sala.protocolo.equals("UDP")) {
            javax.swing.JOptionPane.showMessageDialog(this, 
                "El envío de imágenes está desactivado en salas UDP (límite de 64KB). Utiliza TCP.", 
                "Aviso", javax.swing.JOptionPane.WARNING_MESSAGE);
            return;
        }

        javax.swing.UIManager.put("FileChooser.readOnly", Boolean.TRUE);
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Archivos de Imagen (*.png, *.jpg, *.jpeg)", "png", "jpg", "jpeg"));
        chooser.setDialogTitle("Seleccionar imagen para enviar...");

        if (chooser.showOpenDialog(this) == javax.swing.JFileChooser.APPROVE_OPTION) {
            java.io.File archivo = chooser.getSelectedFile();
            
            if (archivo.length() > 2 * 1024 * 1024) {
                javax.swing.JOptionPane.showMessageDialog(this, "Imagen muy pesada. Máximo 2MB.", "Error", javax.swing.JOptionPane.ERROR_MESSAGE);
                return;
            }

            try {
                String base64 = Launcher.ChatUI.GestorImagenes.codificarImagenABase64(archivo);
                // Envíamos como mensaje normal 
                clienteRed.enviar("MENSAJE:[IMG_BASE64]" + base64 + "[/IMG_BASE64]");
            } catch (Exception e) {
                agregarHTMLAlHistorial("<p style='color:red;'>Error al enviar la imagen.</p>");
            }
        }
    }//GEN-LAST:event_btnInsertarActionPerformed

    private void btnEmojiActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnEmojiActionPerformed
        // TODO add your handling code here:
        if (menuEmojis == null) {
            inicializarMenuEmojis();
        }
        
        int x = 0;
        int y = -menuEmojis.getPreferredSize().height; 
        menuEmojis.show(btnEmoji, x, y);
    }//GEN-LAST:event_btnEmojiActionPerformed

    private void btnToggleBoldActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnToggleBoldActionPerformed
        // TODO add your handling code here:
        String seleccionado = txtMensaje.getSelectedText();
        if (seleccionado != null) {
            txtMensaje.replaceSelection("**" + seleccionado + "**");
        } else {
            txtMensaje.replaceSelection("****");
            txtMensaje.setCaretPosition(txtMensaje.getCaretPosition() - 2);
        }
        txtMensaje.requestFocus();
    }//GEN-LAST:event_btnToggleBoldActionPerformed

    private void btnToggleItalicActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnToggleItalicActionPerformed
        // TODO add your handling code here:
        String seleccionado = txtMensaje.getSelectedText();

        if (seleccionado != null) {

            txtMensaje.replaceSelection("*" + seleccionado + "*");

        } else {

            txtMensaje.replaceSelection("**");

            txtMensaje.setCaretPosition(txtMensaje.getCaretPosition() - 1);

        }

        txtMensaje.requestFocus();
    }//GEN-LAST:event_btnToggleItalicActionPerformed

    private void btnToggleUnderlinedActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnToggleUnderlinedActionPerformed
        // TODO add your handling code here:
        String seleccionado = txtMensaje.getSelectedText();

        if (seleccionado != null) {

            txtMensaje.replaceSelection("__" + seleccionado + "__");

        } else {

            txtMensaje.replaceSelection("____");

            txtMensaje.setCaretPosition(txtMensaje.getCaretPosition() - 2);

        }

        txtMensaje.requestFocus();
    }//GEN-LAST:event_btnToggleUnderlinedActionPerformed

    /**
     * @param args the command line arguments
     */
    public static void main(String args[]) {
        /* Set the Nimbus look and feel */
        //<editor-fold defaultstate="collapsed" desc=" Look and feel setting code (optional) ">
        /* If Nimbus (introduced in Java SE 6) is not available, stay with the default look and feel.
         * For details see http://download.oracle.com/javase/tutorial/uiswing/lookandfeel/plaf.html 
         */
        try {
            for (javax.swing.UIManager.LookAndFeelInfo info : javax.swing.UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    javax.swing.UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException ex) {
            logger.log(java.util.logging.Level.SEVERE, null, ex);
        }
        //</editor-fold>

    }

    // Método para guardar la imagen y generar su ruta HTML
    private String procesarImagenHTML(String base64) {
        try {
            javax.swing.ImageIcon iconoOriginal = Launcher.ChatUI.GestorImagenes.decodificarBase64AImagen(base64);
            javax.swing.ImageIcon iconoChico = Launcher.ChatUI.GestorImagenes.redimensionarIcono(iconoOriginal, 200, 200);

            java.io.File carpetaCache = new java.io.File("cache_chat");
            if (!carpetaCache.exists()) carpetaCache.mkdir();

            java.io.File archivoTemp = new java.io.File(carpetaCache, "img_" + System.currentTimeMillis() + ".png");
            java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(
                iconoChico.getIconWidth(), iconoChico.getIconHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics g = bi.createGraphics();
            iconoChico.paintIcon(null, g, 0, 0);
            g.dispose();
            javax.imageio.ImageIO.write(bi, "png", archivoTemp);

            return "<br><img src='file:///" + archivoTemp.getAbsolutePath().replace("\\", "/") + "'><br>";
        } catch (Exception e) {
            return "<p style='color:red;'>[Error al cargar imagen]</p>";
        }
    }
    
    private void inicializarMenuEmojis() {
        menuEmojis = new javax.swing.JPopupMenu();
        // panel
        javax.swing.JPanel panelEmojis = new javax.swing.JPanel(new java.awt.GridLayout(4, 5, 2, 2));
        panelEmojis.setBackground(java.awt.Color.WHITE);

        String[] emojis = {
            "😀", "😂", "🤣", "😊", "😍",
            "😒", "😭", "😤", "😎", "🤔",
            "👍", "👎", "👏", "🙌", "🔥",
            "❤️", "💔", "👀", "🎉", "✨"
        };

        java.awt.Font fontEmoji = new java.awt.Font("Segoe UI Emoji", java.awt.Font.PLAIN, 18);

        for (String emoji : emojis) {
            javax.swing.JButton btn = new javax.swing.JButton(emoji);
            btn.setFont(fontEmoji);
            btn.setFocusable(false);
            btn.setBackground(java.awt.Color.WHITE);
            btn.setBorder(javax.swing.BorderFactory.createEmptyBorder(5, 5, 5, 5));
            btn.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
            
            btn.addActionListener(e -> {
                txtMensaje.insert(emoji, txtMensaje.getCaretPosition());
                txtMensaje.requestFocus();
                menuEmojis.setVisible(false); 
            });
            
            btn.addMouseListener(new java.awt.event.MouseAdapter() {
                public void mouseEntered(java.awt.event.MouseEvent evt) { btn.setBackground(new java.awt.Color(230, 230, 230)); }
                public void mouseExited(java.awt.event.MouseEvent evt) { btn.setBackground(java.awt.Color.WHITE); }
            });

            panelEmojis.add(btn);
        }
        
        menuEmojis.add(panelEmojis);
    }
    
    public void desconectarChat() {
        try {
            if (clienteRed != null) {
                clienteRed.enviar("SALIR:");
                clienteRed.cerrar();
            }
        } catch (Exception e) {
        }
        this.dispose();
    }
    
    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnEmoji;
    private javax.swing.JButton btnEnviar;
    private javax.swing.JButton btnInsertar;
    private javax.swing.JButton btnSalir;
    private javax.swing.JButton btnToggleBold;
    private javax.swing.JButton btnToggleItalic;
    private javax.swing.JButton btnToggleUnderlined;
    private javax.swing.JPanel jPanel1;
    private javax.swing.JPanel jPanel3;
    private javax.swing.JScrollPane jScrollPane1;
    private javax.swing.JScrollPane jScrollPane2;
    private javax.swing.JTextPane jTextPane1;
    private javax.swing.JPanel panelChat;
    private javax.swing.JTextArea txtMensaje;
    // End of variables declaration//GEN-END:variables
}
