package ChatServer;

import java.io.*;
import java.net.*;
import java.util.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Servidor de chat multi-protocolo (TCP/UDP) con soporte para hasta 5 clientes simultáneos.
 *
 * <p>Uso de Factory para abstraer la capa de red, permitiendo así cambiar 
 * de protocolos con un solo parámetro al inicio.
 * La lógica central reside en {@link GestorChat}, compartida por ambas tcp/udp.</p>
 *
 * <p><b>Comandos de administrador disponibles en consola:</b></p>
 * <ul>
 *   <li>{@code /userlist}             — Lista usuarios conectados.</li>
 *   <li>{@code /kick [usuario]}       — Expulsa a un usuario.</li>
 *   <li>{@code /msg [usuario] [texto]} — Mensaje privado desde el servidor.</li>
 * </ul>
 *
 * @author yazid revilla
 */
public class ChatServer {

    /** Puerto fijo en el que el servidor escucha las conexiones. */
    private static final int PORT = 7777;

    // === Constantes ANSI para color en la consola del servidor ===
    private static final String RESET       = "\033[0m";
    private static final String BOLD        = "\033[1m";
    private static final String RED_BOLD    = "\033[1;31m";
    private static final String GREEN_BOLD  = "\033[1;32m";
    private static final String BLUE_BOLD   = "\033[1;34m";
    private static final String CYAN_BOLD   = "\033[1;36m";
    private static final String PURPLE_BOLD = "\033[1;35m";

    // =========================================================================
    // INTERFACES DE ABSTRACCIÓN DE RED
    // =========================================================================

    /**
     * Abstracción de la conexión a un cliente individual.
     * Permite que {@link GestorChat} opere sin conocer el protocolo subyacente.
     */
    interface IConexionCliente {
        /**
         * Envía un mensaje de texto al cliente.
         * @param msj Texto a enviar (sin salto de línea).
         */
        void enviar(String msj);

        /** Cierra la conexión con el cliente de forma ordenada. */
        void cerrar();

        /**
         * Manda el id único de cada conexión.
         * En TCP es la dirección remota; en UDP es IP:puerto del datagrama.
         * @return Cadena identificadora de la conexión.
         */
        String getId();
    }

    /**
     * Abstracción de un servidor de red que escucha un puerto dado.
     */
    interface IServidorRed {
        /**
         * Inicia el servidor y comienza a aceptar clientes en el puerto indicado.
         * Este método bloquea el hilo actual hasta que el servidor se detenga.
         * @param puerto Número de puerto en el que escucha.
         */
        void iniciar(int puerto);
    }

    /**
     * Fábrica de servidores de red..
     */
    static class ServidorFactory {
        /**
         * Crea y regresa una implementación de {@link IServidorRed} según la opción que sea elegida.
         * @param opcion 1 para TCP, 2 para UDP.
         * @return Instancia del servidor correspondiente.
         * @throws IllegalArgumentException si la opción no es 1 ni 2.
         */
        static IServidorRed crear(int opcion) {
            if (opcion == 1) return new ServidorTCP();
            if (opcion == 2) return new ServidorUDP();
            throw new IllegalArgumentException("Opción no válida");
        }
    }

    // ==========================================================================
    // GESTOR CENTRAL DE LÓGICA Y USUARIOS
    // ==========================================================================

    /**
     * Lógica del chat: gestión de usuarios, mensajes y desconexiones.
     *
     * <p>Todos los métodos públicos están {@code synchronized} para la
     * consistencia en múltiples clientes TCP simultáneos (multi-hilo).</p>
     */
    static class GestorChat {

        /** Número máximo de clientes permitidos en la sala al mismo tiempo. */
        private static final int MAX_CLIENTES = 5;

        /** Tiempo máximo sin actividad antes de desconectar un cliente UDP. */
        private static final long TIMEOUT_UDP_MS = 90_000;

        /** REGEX para validar nombres de usuario: 3-15 caracteres alfanuméricos. */
        private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9]{3,15}$");

        /** Mapa de nombre de usuario a conexión activa. */
        private static Map<String, IConexionCliente> usuarios = new ConcurrentHashMap<>();

        /**
         * Mapa inverso de ID a nombre de usuario.
         * Es para identificar quién envía un mensaje a partir del socket.
         */
        private static Map<String, String> idToUser = new ConcurrentHashMap<>();

        /**
         * Registro de última actividad usado para UDP y detectar clientes fantasma.
         */
        private static Map<String, Long> ultimaActividad = new ConcurrentHashMap<>();

        /** Formateador de fecha/hora para los timestamps de los mensajes. */
        private static DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

        /**
         * Procesa un mensaje entrante de cualquier cliente (TCP o UDP).
         *
         * <p>Mensajes reconocidos:</p>
         * <ul>
         *   <li>{@code STATUS_CHECK}          — Consulta de estado.</li>
         *   <li>{@code PING:}                 — Heartbeat UDP.</li>
         *   <li>{@code REGISTRO:[nombre]}     — Solicitud de registro de usuario.</li>
         *   <li>{@code MENSAJE:[texto]}        — Mensaje broadcast a toda la sala.</li>
         *   <li>{@code PRIVADO:[dest]:[texto]} — Mensaje privado a un usuario específico.</li>
         *   <li>{@code COMANDO:USERLIST}       — Solicita la lista de usuarios conectados.</li>
         *   <li>{@code SALIR:}                 — Desconexión voluntaria del cliente.</li>
         * </ul>
         *
         * @param conexion Conexión del cliente que envió el mensaje.
         * @param mensaje  Texto recibido con el prefijo del protocolo correspondiente.
         */
        public static synchronized void procesarEntrada(IConexionCliente conexion, String mensaje) {
            String idConexion = conexion.getId();

            // Actualizar el timestamp de actividad en cada mensaje recibido para clientes udp.
            ultimaActividad.put(idConexion, System.currentTimeMillis());

            // Heartbeat UDP para avisar que está activo
            if (mensaje.equals("PING:")) return;

            // Revisa el estado desde el lobby (FrmLobby)
            if (mensaje.equals("STATUS_CHECK")) {
                conexion.enviar("STATUS:" + usuarios.size() + ":" + MAX_CLIENTES);
                conexion.cerrar();
                return;
            }

            // --- Fase de registro ---
            if (mensaje.startsWith("REGISTRO:")) {
                String nombre = mensaje.substring(9).trim();

                if (!USERNAME_PATTERN.matcher(nombre).matches()) {
                    // Nombre con caracteres inválidos o muy corto
                    conexion.enviar("NOMBRE_INVALIDO");
                } else if (usuarios.size() >= MAX_CLIENTES) {
                    // Sala llena
                    conexion.enviar("SERVIDOR_LLENO");
                } else if (usuarioExiste(nombre)) {
                    // Nombre ya en uso
                    conexion.enviar("USUARIO_REPETIDO");
                } else {
                    // Registro exitoso
                    usuarios.put(nombre, conexion);
                    idToUser.put(idConexion, nombre);
                    conexion.enviar("REGISTRO_EXITOSO");

                    System.out.println(GREEN_BOLD + "[INFO] Usuario registrado: " + nombre
                            + " desde " + idConexion + RESET);

                    String fechaHora = LocalDateTime.now().format(formatter);
                    broadcast(BOLD + "[" + fechaHora + "] --- " + nombre + " se ha unido a la sala ---" + RESET);
                }
            }
            // --- Desconexión voluntaria ---
            else if (mensaje.startsWith("SALIR:")) {
                desconectar(conexion);
            }
            // --- Comando de lista de usuarios ---
            else if (mensaje.equals("COMANDO:USERLIST")) {
                String remitente = idToUser.get(idConexion);
                if (remitente != null) {
                    enviarListaUsuarios(remitente);
                }
            }
            // --- Mensajes de chat (broadcast o privado) ---
            else {
                String remitente = idToUser.get(idConexion);
                if (remitente != null) {
                    String fechaHora = LocalDateTime.now().format(formatter);

                    if (mensaje.startsWith("PRIVADO:")) {
                        // Formato esperado: "PRIVADO:[destinatario]:[texto]"
                        String[] partes = mensaje.split(":", 3);
                        if (partes.length == 3) {
                            enviarPrivado(remitente, partes[1], partes[2], fechaHora);
                        }
                    } else if (mensaje.startsWith("MENSAJE:")) {
                        String texto = mensaje.substring(8);

                        // El remitente ve "Tú:" en su propio mensaje
                        String mensajeNormal = "[" + fechaHora + "] " + remitente + ": " + texto;
                        String mensajePropio = BLUE_BOLD + "[" + fechaHora + "] Tú: " + texto + RESET;

                        System.out.println(BLUE_BOLD + "[CHAT GLOBAL] " + mensajeNormal + RESET);

                        for (Map.Entry<String, IConexionCliente> entry : usuarios.entrySet()) {
                            if (entry.getKey().equals(remitente)) {
                                entry.getValue().enviar(mensajePropio);
                            } else {
                                entry.getValue().enviar(mensajeNormal);
                            }
                        }
                    }
                }
            }
        }

        // =====================================================================
        // COMANDOS DE LA CONSOLA DE ADMINISTRADOR
        // =====================================================================

        /**
         * Procesa los comandos escritos directamente en la consola del servidor.
         *
         * <p>Comandos disponibles: {@code /userlist}, {@code /kick [usuario]},
         * {@code /msg [usuario] [texto]}.</p>
         *
         * @param comando Línea de texto ingresada en la consola del administrador.
         */
        public static synchronized void procesarComandoServidor(String comando) {
            if (comando.equalsIgnoreCase("/userlist")) {
                System.out.println(CYAN_BOLD + "--- Usuarios Conectados ("
                        + usuarios.size() + "/" + MAX_CLIENTES + ") ---" + RESET);
                for (String user : usuarios.keySet()) {
                    System.out.println("- " + user);
                }
                System.out.println(CYAN_BOLD + "-----------------------------" + RESET);
            }
            else if (comando.startsWith("/kick ")) {
                String target = comando.substring(6).trim();
                IConexionCliente c = obtenerConexionPorNombre(target);
                if (c != null) {
                    c.enviar(RED_BOLD + "Has sido expulsado del servidor por un administrador." + RESET);
                    desconectar(c);
                    System.out.println(GREEN_BOLD + "[ADMIN] Usuario " + target + " expulsado exitosamente." + RESET);
                } else {
                    System.out.println(RED_BOLD + "[ERROR] Usuario '" + target + "' no encontrado." + RESET);
                }
            }
            else if (comando.startsWith("/msg ")) {
                String[] partes = comando.split(" ", 3);
                if (partes.length == 3) {
                    String target = partes[1];
                    String msj = partes[2];
                    IConexionCliente c = obtenerConexionPorNombre(target);
                    if (c != null) {
                        String fechaHora = LocalDateTime.now().format(formatter);
                        c.enviar(PURPLE_BOLD + "[" + fechaHora + "] (Privado de SERVIDOR): " + msj + RESET);
                        System.out.println(PURPLE_BOLD + "[ADMIN] Mensaje enviado a " + target + RESET);
                    } else {
                        System.out.println(RED_BOLD + "[ERROR] Usuario '" + target + "' no encontrado." + RESET);
                    }
                } else {
                    System.out.println(RED_BOLD + "[ERROR] Formato incorrecto. Usa: /msg usuario mensaje" + RESET);
                }
            }
            else if (!comando.trim().isEmpty()) {
                System.out.println(RED_BOLD
                        + "[AYUDA] Comandos válidos: /userlist, /kick [usuario], /msg [usuario] [mensaje]"
                        + RESET);
            }
        }

        /**
         * Desconecta a un cliente, lo elimina de los mapas y le avisa a la sala.
         *
         * @param conexion Conexión del cliente a desconectar.
         */
        public static synchronized void desconectar(IConexionCliente conexion) {
            String nombre = idToUser.remove(conexion.getId());
            ultimaActividad.remove(conexion.getId());

            if (nombre != null) {
                usuarios.remove(nombre);
                System.out.println(RED_BOLD + "[INFO] Usuario desconectado: " + nombre + RESET);

                String fechaHora = LocalDateTime.now().format(formatter);
                broadcast(BOLD + "[" + fechaHora + "] --- " + nombre + " ha salido de la sala ---" + RESET);
            }
            conexion.cerrar();
        }

        // =====================================================================
        // WATCHDOG UDP
        // =====================================================================

        /**
         * Inicia el hilo watchdog para detectar clientes UDP inactivos.
         *
         * <p><b>Parámetros de tiempo:</b></p>
         * <ul>
         *   <li>Revisión cada 30 s.</li>
         *   <li>Timeout de inactividad: {@value #TIMEOUT_UDP_MS} ms.</li>
         *   <li>El cliente envía un {@code PING:} cada 30s.</li>
         * </ul>
         */
        public static void iniciarWatchdogUDP() {
            Thread watchdog = new Thread(() -> {
                while (true) {
                    try {
                        // Esperar 30 segundos entre cada revisión
                        Thread.sleep(30_000);
                        long ahora = System.currentTimeMillis();
                        
                        for (Map.Entry<String, Long> entry : new HashMap<>(ultimaActividad).entrySet()) {
                            if (ahora - entry.getValue() > TIMEOUT_UDP_MS) {
                                String idConexion = entry.getKey();
                                IConexionCliente conexion = buscarConexionPorId(idConexion);

                                if (conexion != null) {
                                    System.out.println(RED_BOLD
                                            + "[WATCHDOG] Cliente UDP sin actividad, desconectando: "
                                            + idConexion + RESET);
                                    desconectar(conexion);
                                } else {
                                    ultimaActividad.remove(idConexion);
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            });
            watchdog.setDaemon(true);
            watchdog.setName("watchdog-udp");
            watchdog.start();
            System.out.println(CYAN_BOLD + "[INFO] Watchdog UDP iniciado (timeout: "
                    + (TIMEOUT_UDP_MS / 1000) + "s)." + RESET);
        }

        // =====================================================================
        // MÉTODOS DE APOYO
        // =====================================================================

        /**
         * Verifica si un nombre de usuario ya está registrado en la sala.
         *
         * @param nombreNuevo Nombre a verificar.
         * @return {@code true} si el nombre ya está en uso.
         */
        private static boolean usuarioExiste(String nombreNuevo) {
            for (String userConectado : usuarios.keySet()) {
                if (userConectado.equalsIgnoreCase(nombreNuevo)) return true;
            }
            return false;
        }

        /**
         * Envía un mensaje a todos los usuarios conectados.
         *
         * @param msj Texto a difundir que  puede incluir códigos ANSI.
         */
        private static void broadcast(String msj) {
            for (IConexionCliente c : usuarios.values()) {
                c.enviar(msj);
            }
        }

        /**
         * Envía un mensaje privado entre dos usuarios.
         * Solo el remitente y el destinatario reciben el mensaje.
         *
         * @param rem   Nombre del remitente.
         * @param dest  Nombre del destinatario.
         * @param msj   Texto del mensaje privado.
         * @param fecha Timestamp formateado para incluir en el mensaje.
         */
        private static void enviarPrivado(String rem, String dest, String msj, String fecha) {
            IConexionCliente cDest = obtenerConexionPorNombre(dest);
            IConexionCliente cRem  = usuarios.get(rem);

            if (cDest != null) {
                cDest.enviar(PURPLE_BOLD + "[" + fecha + "] (Privado de " + rem + "): " + msj + RESET);
                cRem.enviar(PURPLE_BOLD  + "[" + fecha + "] (Privado para " + dest + "): " + msj + RESET);
            } else {
                cRem.enviar(RED_BOLD + "SERVIDOR: El usuario '" + dest + "' no está conectado." + RESET);
            }
        }

        /**
         * Envía al usuario que la solicitó, la lista actual de usuarios.
         *
         * @param solicitante Nombre del usuario que ejecutó el comando {@code /userlist}.
         */
        private static void enviarListaUsuarios(String solicitante) {
            IConexionCliente cSolicitante = usuarios.get(solicitante);
            if (cSolicitante != null) {
                StringBuilder sb = new StringBuilder();
                sb.append(CYAN_BOLD).append("--- Usuarios Conectados (")
                  .append(usuarios.size()).append("/").append(MAX_CLIENTES).append(") ---\n");
                for (String user : usuarios.keySet()) {
                    sb.append("- ").append(user);
                    if (user.equals(solicitante)) sb.append(" (Tú)");
                    sb.append("\n");
                }
                sb.append("-----------------------------").append(RESET);
                cSolicitante.enviar(sb.toString());
            }
        }

        /**
         * Busca una conexión que esté activa por nombre de usuario.
         *
         * @param nombre Nombre del usuario buscado.
         * @return La {@link IConexionCliente} correspondiente, o {@code null} si no existe.
         */
        private static IConexionCliente obtenerConexionPorNombre(String nombre) {
            for (Map.Entry<String, IConexionCliente> entry : usuarios.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(nombre)) return entry.getValue();
            }
            return null;
        }

        /**
         * Busca una conexión activa por su ID de conexión.
         * Lo usa el watchdog UDP
         *
         * @param id Identificador de conexión (resultado de {@link IConexionCliente#getId()}).
         * @return La {@link IConexionCliente} correspondiente, o {@code null} si no existe.
         */
        private static IConexionCliente buscarConexionPorId(String id) {
            for (Map.Entry<String, String> entry : idToUser.entrySet()) {
                if (entry.getKey().equals(id)) {
                    return usuarios.get(entry.getValue());
                }
            }
            return null;
        }
    }

    // =========================================================================
    // IMPLEMENTACIÓN DE RED TCP
    // =========================================================================

    /**
     * Implementación TCP de {@link IConexionCliente}.
     */
    static class ConexionTCP implements IConexionCliente {
        private final Socket socket;
        private final PrintWriter out;
        private final String id;

        /**
         * Crea una ConexionTCP a partir de un socket.
         * @param socket Socket TCP del cliente recién conectado.
         * @throws IOException Si no se puede obtener.
         */
        public ConexionTCP(Socket socket) throws IOException {
            this.socket = socket;
            this.out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            this.id = socket.getRemoteSocketAddress().toString();
        }

        @Override public void enviar(String msj) { out.println(msj); }
        @Override public void cerrar()           { try { socket.close(); } catch (Exception e) {} }
        @Override public String getId()          { return id; }
    }

    /**
     * Implementación TCP de {@link IServidorRed}.
     * Acepta conexiones entrantes y les asigna un hilo a cada cliente.
     */
    static class ServidorTCP implements IServidorRed {
        /**
         * Inicia el servidor TCP, aceptando clientes en el puerto indicado.
         * Cada cliente se maneja en su propio hilo para evitar bloqueos.
         * @param puerto Puerto en el que escuchar.
         */
        @Override
        public void iniciar(int puerto) {
            try (ServerSocket server = new ServerSocket(puerto)) {
                System.out.println(GREEN_BOLD + "\n\t=== SERVIDOR TCP INICIADO EN EL PUERTO " + puerto + " ===" + RESET);

                while (true) {
                    Socket socket = server.accept();
                    ConexionTCP conexion = new ConexionTCP(socket);

                    // Cada cliente obtiene su propio hilo para lectura asíncrona
                    new Thread(() -> {
                        try (BufferedReader in = new BufferedReader(
                                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                            String msj;
                            while ((msj = in.readLine()) != null) {
                                GestorChat.procesarEntrada(conexion, msj);
                            }
                        } catch (IOException e) {
                            // Desconexión
                        } finally {
                            // Garantiza que la sala se notifique
                            GestorChat.desconectar(conexion);
                        }
                    }).start();
                }
            } catch (IOException e) {
                System.err.println(RED_BOLD + "Error TCP: " + e.getMessage() + RESET);
            }
        }
    }

    // =========================================================================
    // IMPLEMENTACIÓN DE RED UDP
    // =========================================================================

    /**
     * Implementación UDP de {@link IConexionCliente}.
     */
    static class ConexionUDP implements IConexionCliente {
        private final DatagramSocket socket;
        private final InetSocketAddress address;

        /**
         * @param socket  Socket UDP del servidor.
         * @param address Dirección IP y puerto del cliente remoto.
         */
        public ConexionUDP(DatagramSocket socket, InetSocketAddress address) {
            this.socket  = socket;
            this.address = address;
        }

        @Override
        public void enviar(String msj) {
            try {
                byte[] d = msj.getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(d, d.length, address.getAddress(), address.getPort()));
            } catch (Exception e) {
                // Fallo al enviar
            }
        }

        /** No aplica en UDP, puesto que el watchdog se encarga. */
        @Override public void cerrar()  {}

        @Override public String getId() { return address.toString(); }
    }

    /**
     * Implementación UDP de {@link IServidorRed}.
     * Inicia el watchdog para detectar clientes que se van sin avisar.
     */
    static class ServidorUDP implements IServidorRed {
        /**
         * Inicia el servidor UDP y el watchdog de inactividad.
         * @param puerto Puerto en el que escuchar datagramas.
         */
        @Override
        public void iniciar(int puerto) {
            try (DatagramSocket socket = new DatagramSocket(puerto)) {
                System.out.println(GREEN_BOLD + "\n\t=== SERVIDOR UDP INICIADO EN EL PUERTO " + puerto + " ===" + RESET);

                // Detecta clientes UDP que se van sin enviar "SALIR:".
                GestorChat.iniciarWatchdogUDP();

                byte[] buffer = new byte[65507];

                while (true) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    String msj = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    InetSocketAddress addr = new InetSocketAddress(packet.getAddress(), packet.getPort());

                    // Cada datagrama crea una ConexionUDP con la misma dirección:
                    // GestorChat la identifica por el ID (IP:puerto).
                    GestorChat.procesarEntrada(new ConexionUDP(socket, addr), msj);
                }
            } catch (IOException e) {
                System.err.println(RED_BOLD + "Error UDP: " + e.getMessage() + RESET);
            }
        }
    }

    // =========================================================================
    // PUNTO DE ENTRADA
    // =========================================================================

    /**
     * Punto de entrada del servidor.
     *
     * <p>Solicita al administrador que elija el protocolo (TCP o UDP),
     * muestra la IP local para que los clientes puedan conectarse,
     * lanza un hilo de consola para comandos de administrador e
     * inicia el servidor con el protocolo seleccionado.</p>
     *
     * @param args
     */
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8.name());

        System.out.println("\n\t" + BOLD + "=== CONFIGURACIÓN DEL SERVIDOR ===" + RESET);
        System.out.println("1. TCP\n2. UDP");

        System.out.print("\n" + CYAN_BOLD + "Elige el protocolo a utilizar: " + RESET);
        int opc = scanner.nextInt();
        scanner.nextLine();

        try {
            String miIp = InetAddress.getLocalHost().getHostAddress();

            System.out.println(GREEN_BOLD + "\n[INFO] Configurando servidor..." + RESET);
            System.out.println(CYAN_BOLD + "[Red] IP local de esta computadora: " + miIp + RESET);
            System.out.println(CYAN_BOLD + "[Red] Los clientes deben usar esta IP para conectarse." + RESET);
            System.out.println(BOLD + "\n\t--- Consola de Administrador Activada ---" + RESET);
            System.out.println(BOLD + "Comandos disponibles:" + RESET);
            System.out.println("  /userlist              - Muestra los usuarios conectados.");
            System.out.println("  /kick [usuario]        - Expulsa a un usuario de la sala.");
            System.out.println("  /msg [usuario] [texto] - Envía un mensaje privado como SERVIDOR.");
            System.out.println(BOLD + "\t-----------------------------------------\n");

            // Hilo que lee comandos del administrador sin bloquear el servidor principal
            Thread consolaServidor = new Thread(() -> {
                while (true) {
                    String comando = scanner.nextLine();
                    GestorChat.procesarComandoServidor(comando);
                }
            });
            consolaServidor.setDaemon(true);
            consolaServidor.setName("consola-admin");
            consolaServidor.start();

            IServidorRed servidor = ServidorFactory.crear(opc);
            servidor.iniciar(PORT);

        } catch (Exception e) {
            System.err.println(RED_BOLD + "Error de inicialización: " + e.getMessage() + RESET);
        }
    }
}