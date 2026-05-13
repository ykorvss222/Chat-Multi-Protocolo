package Launcher.ChatUI;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Base64;
import javax.imageio.ImageIO;

public class GestorImagenes {

    // Conversor de imagen a un String Base64
    public static String codificarImagenABase64(File archivoImagen) {
        try {
            FileInputStream fileInputStreamReader = new FileInputStream(archivoImagen);
            byte[] bytes = new byte[(int) archivoImagen.length()];
            fileInputStreamReader.read(bytes);
            fileInputStreamReader.close();
            
            // Regresa la cadena de texto base64
            return Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            System.err.println("Error al codificar imagen: " + e.getMessage());
            return null;
        }
    }

    // Convierte el string base64 a imagen
    public static ImageIcon decodificarBase64AImagen(String base64String) {
        if (base64String == null || base64String.isEmpty()) {
            return null;
        }
        try {
            byte[] imageBytes = Base64.getDecoder().decode(base64String);
            ByteArrayInputStream bis = new ByteArrayInputStream(imageBytes);
            BufferedImage bImage = ImageIO.read(bis);
            bis.close();
            
            return new ImageIcon(bImage);
        } catch (IOException e) {
            System.err.println("Error al decodificar imagen: " + e.getMessage());
            return null;
        }
    }
    
    // Método para ajustar el tamaño de las imágenes
    public static ImageIcon redimensionarIcono(ImageIcon iconoOriginal, int ancho, int alto) {
        if (iconoOriginal == null) return null;
        
        Image img = iconoOriginal.getImage();
        Image imgEscalada = img.getScaledInstance(ancho, alto, java.awt.Image.SCALE_SMOOTH);
        return new ImageIcon(imgEscalada);
    }
}