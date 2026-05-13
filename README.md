# 💬 Chat Multi-Protocolo (TCP/UDP) con UI estilo MSN Messenger 

## 📖 Descripción del Proyecto
Este sistema de chat desarrollado en **Java** que implementa una arquitectura flexible haciendo uso de **Factory** para permitir usar comunicaciones tanto vía **TCP** como **UDP**. 

La aplicación permite a los usuarios crear perfiles personalizados con avatares, gestionar una lista de servidores (salas) y comunicarse en tiempo real mediante mensajes públicos y privados. La interfaz visual está inspirada en MSN Messenger, utilizando componentes de **Java Swing** y renderización **HTML/CSS** para poder utilizar estilos de texto y emojis.

### ✨ Características Principales
* **Soporte Multi-protocolo:** Selección dinámica entre TCP (conexión persistente) y UDP (datagramas con sistema de *watchdog* y *heartbeat* para desconexión automática).
* **Gestión de Perfiles:** Identidad única mediante UUID y personalización de avatar codificado en Base64.
* **Salas Personalizadas:** Almacenamiento local de servidores en archivos `.properties`.
* **Funciones de Chat:** Soporte para **negritas**, *cursivas*, __subrayado__, emojis y envío de imágenes exclusivo de TCP (debido a limitaciones UDP).
* **Administración:** Consola de servidor con comandos para listar, expulsar (`/kick`) o enviar mensajes globales.

---

## 🛠️ Tecnologías Utilizadas
| Tecnología | Uso |
| :--- | :--- |
| **Java SE** | Lenguaje utilizado para el sistema. |
| **Java Swing** | Desarrollo de la interfaz gráfica de usuario (GUI). |
| **Sockets (TCP/UDP)** | Comunicación de red entre cliente y servidor. |
| **HTML/CSS** | Renderizado de mensajes dentro del chat. |
| **Base64** | Procesamiento y envío de imágenes y avatares. |
| **Java Properties** | Persistencia de datos de usuario y salas. |

---

## 🚀 Instalación y Ejecución

### Requisitos Previos
* Java JDK 8 o superior.
* IDE NetBeans (recomendado).

### Pasos para Ejecutar
1.  **Clonar el repositorio:**
    ```bash
    git clone [https://github.com/ykorvss222/Chat-Multi-Protocolo.git](https://github.com/ykorvss222/Chat-Multi-Protocolo.git)
    ```
2.  **Iniciar el Servidor:**
    Ejecuta la clase `ChatServer.java`. Selecciona el protocolo (1 para TCP, 2 para UDP) y verifica la IP local mostrada en consola.
3.  **Iniciar el Cliente:**
    Ejecuta la clase `FrmLogin.java` para abrir la interfaz gráfica. Ingresa tu nombre de usuario y añade la IP del servidor en el Lobby.

---

## 👥 Autor
* **Yazid Revilla**

## 🛜 Materia
* Redes

## 📚 Docente
* Mario Arispuro

---

## 📸 Evidencia de Funcionamiento

### 1. Inicio del Servidor
> ![Consola del Servidor](temp)
> *Muestra el servidor escuchando en el puerto 7777.*

### 2. Registro de Usuario
> ![Pantalla de Login](temp)
> *Interfaz de acceso y selección de avatar.*

### 3. Chat Grupal
> ![Sala de Chat](temp)
> *Conversación activa con múltiples usuarios y estilos de texto.*

### 4. Mensajes Privados
> ![Mensaje Privado](temp)
> *Ejemplo del comando `/msg` funcionando entre dos clientes.*

---

## 📂 Estructura del Proyecto
```text
src/
├── ChatCliente/        # Lógica del cliente de consola
├── ChatServer/         # Lógica central del servidor
└── Launcher/
    ├── ChatFrm/        # Ventanas Swing (Login, Lobby, Chat)
    └── ChatUI/         # Gestores de datos, imágenes y salas
