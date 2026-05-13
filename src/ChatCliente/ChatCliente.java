package ChatCliente;

import java.io.*;
import java.net.*;
import java.util.Scanner;
import java.nio.charset.StandardCharsets;

/**
 * Cliente de chat multi-protocolo (TCP/UDP) para conectarse al {@code ChatServer}.
 *
 * <p>Uso de Factory para abstraer la capa de red, permitiendo así cambiar 
 * de protocolos con un solo parámetro al inicio.</p>
 * * <p>El flujo de uso es:</p>
 * <ol>
 * <li>Elegir protocolo (TCP/UDP) e ingresar la IP del servidor.</li>
 * <li>Registrarse con un nombre de usuario único.</li>
 * <li>Escribir mensajes. El hilo receptor muestra los mensajes entrantes en paralelo.</li>
 * </ol>
 *
 * <p><b>Comandos disponibles durante el chat:</b></p>
 * <ul>
 * <li>{@code /msg usuario texto} — Mensaje privado.</li>
 * <li>{@code /userlist}          — Lista de usuarios conectados.</li>
 * <li>{@code exit}               — Salir del chat.</li>
 * </ul>
 *
 * @author yazid revilla
 */
public class ChatCliente {

    /** Puerto del servidor al que el cliente se conecta por defecto.*/
    private static final int SERVER_PORT = 7777;

    /**
     * Intervalo en ms entre cada heartbeat enviado al servidor cuando se usa UDP.
     */
    private static final long HEARTBEAT_INTERVALO_MS = 30_000;

    // === Constantes de color ANSI para la consola del cliente ===
    private static final String RESET     = "\033[0m";
    private static final String BOLD      = "\033[1m";
    private static final String RED_BOLD  = "\033[1;31m";
    private static final String GREEN_BOLD= "\033[1;32m";
    private static final String CYAN_BOLD = "\033[1;36m";

    // =========================================================================
    // INTERFACES DE ABSTRACCIÓN DE RED
    // =========================================================================

    /** Abstracción de un cliente de red. */
    interface IClienteRed {
        /**
         * Establece la conexión con el servidor en la IP y puerto indicados.
         * @param ip   Dirección IP del servidor.
         * @param port Puerto del servidor.
         * @throws IOException Si no es posible establecer la conexión.
         */
        void conectar(String ip, int port) throws IOException;

        /**
         * Envía una cadena de texto al servidor.
         * @param msj Texto a enviar
         * @throws IOException Si el envío falla.
         */
        void enviar(String msj) throws IOException;

        /**
         * Espera y regresa el siguiente mensaje recibido del servidor.
         * Bloquea el hilo hasta que llegue un mensaje.
         * @return Texto recibido, o {@code null} si la conexión se cerró
         * @throws IOException Si ocurre un error de lectura.
         */
        String recibir() throws IOException;

        /** Cierra la conexión */
        void cerrar();
    }

    /**
     * Fábrica de clientes de red
     */
    static class ClienteFactory {
        /**
         * Crea y regresa una implementación de {@link IClienteRed} según la opción que sea elegida.
         * @param opcion 1 para TCP, 2 para UDP.
         * @return Instancia del cliente correspondiente.
         * @throws IllegalArgumentException si la opción no es 1 ni 2.
         */
        static IClienteRed crear(int opcion) {
            if (opcion == 1) return new ClienteTCP();
            if (opcion == 2) return new ClienteUDP();
            throw new IllegalArgumentException("Opción no válida");
        }
    }

    // =========================================================================
    // IMPLEMENTACIÓN DE RED TCP
    // =========================================================================

    /**
     * Implementación TCP de {@link IClienteRed}.
     *
     * <p>Usa un {@link Socket} con texto UTF-8.
     * (La desconexión la detecta automáticamente el servidor al cerrarse el socket)</p>
     */
    static class ClienteTCP implements IClienteRed {
        private Socket socket;
        private BufferedReader in;
        private PrintWriter out;

        /**
         * Abre un socket TCP hacia el servidor y prepara los streams de texto UTF-8.
         * @param ip   IP del servidor.
         * @param port Puerto del servidor.
         * @throws IOException Si no se puede conectar.
         */
        @Override
        public void conectar(String ip, int port) throws IOException {
            socket = new Socket(ip, port);
            in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        }

        @Override public void enviar(String msj)     throws IOException { out.println(msj); }
        @Override public String recibir()            throws IOException { return in.readLine(); }
        @Override public void cerrar()               { try { socket.close(); } catch (Exception e) {} }
    }

    // =========================================================================
    // IMPLEMENTACIÓN DE RED UDP
    // =========================================================================

    /**
     * Implementación UDP de {@link IClienteRed}.
     *
     * <p>Usa un {@link DatagramSocket} sin conexión.
     * Cada llamada a {@link #enviar} y {@link #recibir} trabaja con datagramas que son independientes.
     * Requiere que el hilo de heartbeat esté activo para poder mantener al usuario registrado en el servidor.</p>
     */
    static class ClienteUDP implements IClienteRed {
        private DatagramSocket socket;
        private InetAddress serverAddress;
        private int serverPort;

        /**
         * Crea el socket UDP y resuelve la dirección del servidor.
         * @param ip   IP del servidor.
         * @param port Puerto del servidor.
         * @throws IOException Si no se puede crear el socket o resolver la IP.
         */
        @Override
        public void conectar(String ip, int port) throws IOException {
            socket      = new DatagramSocket();
            serverAddress = InetAddress.getByName(ip);
            serverPort  = port;
        }

        /**
         * Empaqueta el mensaje en un datagrama y lo envía al servidor.
         * @param msj Texto a enviar.
         * @throws IOException Si el envío falla.
         */
        @Override
        public void enviar(String msj) throws IOException {
            byte[] data = msj.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(data, data.length, serverAddress, serverPort));
        }

        /**
         * Espera un datagrama del servidor y regresa su contenido como texto.
         * @return Texto del datagrama recibido.
         * @throws IOException Si la recepción falla.
         */
        @Override
        public String recibir() throws IOException {
            byte[] buffer = new byte[65507];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            return new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
        }

        @Override public void cerrar() { socket.close(); }
    }

    // =========================================================================
    // PUNTO DE ENTRADA
    // =========================================================================

    /**
     * Punto de entrada del cliente.
     *
     * <p>Flujo principal:</p>
     * <ol>
     * <li>El usuario elige protocolo e IP del servidor.</li>
     * <li>Se conecta y completa la fase de registro.</li>
     * <li>Se usa un <b>hilo receptor</b> para mostrar mensajes entrantes sin tener que bloquear la escritura.</li>
     * <li>Si el protocolo es UDP, se lanza un <b>hilo heartbeat</b> que envía {@code PING:}
     * cada {@value #HEARTBEAT_INTERVALO_MS} ms para que el watchdog del servidor
     * no lo considere inactivo y lo desconecte</li>
     * <li>El hilo principal gestiona la escritura de mensajes hasta que el usuario escribe {@code exit}.</li>
     * </ol>
     *
     * @param args Argumentos de línea de comandos
     */
    public static void main(String[] args) {
        // Scanner con soporte UTF-8 para nombres y mensajes con caracteres especiales
        Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8.name());

        System.out.println("\n\t" + BOLD + "=== CLIENTE DE CHAT ===" + RESET);
        System.out.println("1. TCP\n2. UDP");

        System.out.print("\n" + CYAN_BOLD + "Elige una opción: " + RESET);
        int opcion = scanner.nextInt();
        scanner.nextLine();

        System.out.print(CYAN_BOLD + "Ingresa la IP del servidor (o presiona Enter para usar localhost): " + RESET);
        String serverIp = scanner.nextLine().trim();
        if (serverIp.isEmpty()) {
            serverIp = "127.0.0.1"; // dirección por defecto
        }

        IClienteRed cliente = null;
        try {
            cliente = ClienteFactory.crear(opcion);
            cliente.conectar(serverIp, SERVER_PORT);
            System.out.println(GREEN_BOLD + "\n[INFO] Conexión establecida con el servidor en " + serverIp + RESET);

            // --- FASE DE REGISTRO ---
            boolean registrado = false;
            while (!registrado) {
                System.out.print(CYAN_BOLD + "Ingresa tu nombre de usuario: " + RESET);
                String user = scanner.nextLine();

                cliente.enviar("REGISTRO:" + user);
                String respuesta = cliente.recibir();

                switch (respuesta) {
                    case "USUARIO_REPETIDO":
                        System.out.println(RED_BOLD + "⚠️  El nombre ya está en uso. Intenta otro." + RESET);
                        break;
                    case "SERVIDOR_LLENO":
                        System.out.println(RED_BOLD + "❌ El servidor está lleno (máximo 5 usuarios). Saliendo..." + RESET);
                        return;
                    case "NOMBRE_INVALIDO":
                        System.out.println(RED_BOLD + "⚠️  Nombre inválido. Solo letras y números (3-15 caracteres) sin espacios." + RESET);
                        break;
                    case "REGISTRO_EXITOSO":
                        System.out.println(GREEN_BOLD + "\n✅ ¡Conectado a la sala exitosamente!" + RESET);
                        System.out.println(BOLD + "Comandos: '/msg usuario texto' | '/userlist' | 'exit'\n" + RESET);
                        registrado = true;
                        break;
                    default:
                        System.out.println(RED_BOLD + "[WARN] Respuesta inesperada del servidor: " + respuesta + RESET);
                }
            }

            // ---HILO RECEPTOR ---
            IClienteRed finalCliente = cliente;
            Thread hiloReceptor = new Thread(() -> {
                try {
                    while (true) {
                        String msj = finalCliente.recibir();
                        if (msj == null) break; // el servidor cerró la conexión
                        System.out.println(msj);
                    }
                } catch (IOException e) {
                    System.out.println(RED_BOLD + "\n❌ Te has desconectado del servidor." + RESET);
                }
            });
            hiloReceptor.setDaemon(true);
            hiloReceptor.setName("receptor-mensajes");
            hiloReceptor.start();

            // --- HILO HEARTBEAT UDP --
            if (opcion == 2) {
                Thread heartbeat = new Thread(() -> {
                    try {
                        while (true) {
                            Thread.sleep(HEARTBEAT_INTERVALO_MS);
                            finalCliente.enviar("PING:");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (IOException e) {
                        System.out.println(RED_BOLD + "[WARN] Heartbeat UDP detenido: " + e.getMessage() + RESET);
                    }
                });
                heartbeat.setDaemon(true);
                heartbeat.setName("heartbeat-udp");
                heartbeat.start();
                System.out.println(CYAN_BOLD + "[INFO] Heartbeat UDP activo (cada "
                        + (HEARTBEAT_INTERVALO_MS / 1000) + "s)." + RESET);
            }

            // --- CICLO DE ESCRITURA ---
            while (true) {
                String msj = scanner.nextLine();

                if (msj.equalsIgnoreCase("exit")) {
                    cliente.enviar("SALIR:");
                    break;
                }
                else if (msj.equalsIgnoreCase("/userlist")) {
                    cliente.enviar("COMANDO:USERLIST");
                }
                else if (msj.startsWith("/msg ")) {
                    // Formato: "/msg [destinatario] [texto]"
                    String[] partes = msj.split(" ", 3);
                    if (partes.length == 3) {
                        cliente.enviar("PRIVADO:" + partes[1] + ":" + partes[2]);
                    } else {
                        System.out.println(RED_BOLD + "Formato incorrecto. Uso: /msg usuario mensaje" + RESET);
                    }
                }
                else {
                    // Mensaje broadcast a toda la sala
                    cliente.enviar("MENSAJE:" + msj);
                }
            }

        } catch (Exception e) {
            System.err.println(RED_BOLD + "Error de conexión: " + e.getMessage() + RESET);
        } finally {
            // Garantizar que el socket se cierre aunque ocurra cualquier excepción
            if (cliente != null) cliente.cerrar();
        }
    }
}