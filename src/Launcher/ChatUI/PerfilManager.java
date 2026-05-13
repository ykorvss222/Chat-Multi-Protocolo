package Launcher.ChatUI;

import java.io.*;
import java.util.Properties;
import java.util.UUID;

public class PerfilManager {
    // archivo donde guardamos la info
    private static final String ARCHIVO_PERFIL = "mi_perfil.properties";
    
    private String username;
    private String uuid;
    private String avatarBase64;

    // se trata de cargar el perfil existente
    public PerfilManager() {
        cargarPerfil();
    }

    private void cargarPerfil() {
        Properties propiedades = new Properties();
        File archivo = new File(ARCHIVO_PERFIL);

        if (archivo.exists()) {
            // Si el archivo ya existe, lo leemos
            try (FileInputStream fis = new FileInputStream(archivo)) {
                propiedades.load(fis);
                this.username = propiedades.getProperty("username", "UsuarioDesconocido");
                this.uuid = propiedades.getProperty("uuid");
                this.avatarBase64 = propiedades.getProperty("avatarBase64", "");
            } catch (IOException e) {
                System.err.println("Error al leer el perfil: " + e.getMessage());
            }
        } else {
            // si es la primera vez que se abre se crea un nuevo usuario
            this.username = "NuevoUsuario";
            this.uuid = UUID.randomUUID().toString(); // creación de id único por user
            this.avatarBase64 = "";
            guardarPerfil(this.username);
        }
    }

    // método para guardar cambios en el perfil
    public void guardarPerfil(String nuevoNombre) {
        this.username = nuevoNombre;

        Properties propiedades = new Properties();
        propiedades.setProperty("username", this.username);
        propiedades.setProperty("uuid", this.uuid);
        propiedades.setProperty("avatarBase64", this.avatarBase64); 

        try (FileOutputStream fos = new FileOutputStream(ARCHIVO_PERFIL)) {
            propiedades.store(fos, "Configuración del Perfil de Usuario");
            System.out.println("Perfil guardado exitosamente.");
        } catch (IOException e) {
            System.err.println("Error al guardar el perfil: " + e.getMessage());
        }
    }
    
    public void guardarAvatar(String nuevoAvatarBase64) {
        this.avatarBase64 = nuevoAvatarBase64;
        Properties propiedades = new Properties();
        propiedades.setProperty("username", this.username);
        propiedades.setProperty("uuid", this.uuid);
        propiedades.setProperty("avatarBase64", this.avatarBase64);

        try (FileOutputStream fos = new FileOutputStream(ARCHIVO_PERFIL)) {
            propiedades.store(fos, "Configuración del Perfil de Usuario");
        } catch (IOException e) {
            System.err.println("Error al guardar el avatar: " + e.getMessage());
        }
    }

    // GETTERS
    public String getUsername() { return username; }
    public String getUuid() { return uuid; }
    public String getAvatarBase64() { return avatarBase64; }
}