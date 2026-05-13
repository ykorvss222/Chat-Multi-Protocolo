package Launcher.ChatUI;

import java.io.*;
import java.util.*;

public class SalaManager {
    // Archivo donde se guardan las IPs y los Puertos de las salas
    private static final String ARCHIVO_SALAS = "mis_salas.properties";
    private Properties salasGuardadas;

    public SalaManager() {
        salasGuardadas = new Properties();
        cargarSalas();
    }

    private void cargarSalas() {
        File archivo = new File(ARCHIVO_SALAS);
        if (archivo.exists()) {
            try (FileInputStream fis = new FileInputStream(archivo)) {
                salasGuardadas.load(fis);
            } catch (IOException e) {
                System.err.println("Error al cargar las salas: " + e.getMessage());
            }
        } else {
            // sala default localhost
            agregarSala("Sala Local", "127.0.0.1", 7777);
        }
    }

    // método para agregar un servidor nuevo
    public void agregarSala(String nombreSala, String ip, int puerto) {
        // se guarda en "IP:PUERTO"
        salasGuardadas.setProperty(nombreSala, ip + ":" + puerto);
        guardarEnDisco();
    }
    
    public void eliminarSala(String nombreSala) {
        salasGuardadas.remove(nombreSala);
        guardarEnDisco();
    }

    private void guardarEnDisco() {
        try (FileOutputStream fos = new FileOutputStream(ARCHIVO_SALAS)) {
            salasGuardadas.store(fos, "Mis Salas Guardadas - MSN Chat");
        } catch (IOException e) {
            System.err.println("Error al guardar el archivo de salas: " + e.getMessage());
        }
    }

    // manda a la interfaz un mapa con los nombres y direcciones
    public Map<String, String> obtenerSalas() {
        Map<String, String> mapaSalas = new HashMap<>();
        for (String nombre : salasGuardadas.stringPropertyNames()) {
            mapaSalas.put(nombre, salasGuardadas.getProperty(nombre));
        }
        return mapaSalas;
    }
}