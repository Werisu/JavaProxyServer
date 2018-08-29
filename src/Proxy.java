/*
   Acadêmico:		Wellysson
   Matrícula:		14334933
   Disciplina:		Redes II
   Curso: 		TADS
   Date:			20/08/2018
*/

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Scanner;


/**
 * The Proxy creates a Server Socket which will wait for connections on the specified port.
 * Once a connection arrives and a socket is accepted, the Proxy creates a RequestHandler object
 * on a new thread and passes the socket to it to be handled.
 * This allows the Proxy to continue accept further connections while others are being handled.
 * 
 * The Proxy class is also responsible for providing the dynamic management of the proxy through the console 
 * and is run on a separate thread in order to not interrupt the acceptance of socket connections.
 * This allows the administrator to dynamically block web sites in real time. 
 * 
 * The Proxy server is also responsible for maintaining cached copies of the any websites that are requested by
 * clients and this includes the HTML markup, images, css and js files associated with each webpage.
 * 
 * Upon closing the proxy server, the HashMaps which hold cached items and blocked sites are serialized and
 * written to a file and are loaded back in when the proxy is started once more, meaning that cached and blocked
 * sites are maintained.
 *
 */
public class Proxy implements Runnable{


	// Método principal para o programa
	public static void main(String[] args) {
		// Crie uma instância do Proxy e comece a ouvir as conexões
		Proxy myProxy = new Proxy(8085);
		myProxy.listen();	
	}


	private ServerSocket serverSocket;

	/**
	 * Semáforo para Sistema de Gerenciamento de Proxy e Consolide
	 */
	private volatile boolean running = true;


	/**
	 * Estrutura de dados para consulta de ordem constante de itens de cache
	 * Key: URL da página / imagem solicitada.
	 * Value: Arquivo no armazenamento associado a essa chave.
	 */
	static HashMap<String, File> cache;

	/**
	 * Estrutura de dados para consulta constante de pedidos de sites bloqueados.
	 * Key: URL da página / imagem solicitada.
	 * Value: URL da página / imagem solicitada.
	 */
	static HashMap<String, String> blockedSites;

	/**
	 * ArrayList de threads que estão atualmente em execução e atendendo a solicitações.
	 * Esta lista é necessária para unir todos os tópicos no fechamento do servidor
	 */
	static ArrayList<Thread> servicingThreads;



	/**
	 * Crie o servidor proxy
	 * @param port Número da porta para executar o servidor proxy.
	 */
	public Proxy(int port) {

		// Carregar no mapa hash contendo sites armazenados em cache anteriormente e sites bloqueados
		cache = new HashMap<>();
		blockedSites = new HashMap<>();

		// Criar lista de matriz para manter os segmentos de serviço
		servicingThreads = new ArrayList<>();

		// Inicie o gerenciador dinâmico em um encadeamento separado.
		new Thread(this).start();	// Começa a substituir o método run () na parte inferior

		try{
			// Carregar em sites em cache do arquivo
			File cachedSites = new File("cachedSites.txt");
			if(!cachedSites.exists()){
				System.out.println("No cached sites found - creating new file");
				cachedSites.createNewFile();
			} else {
				FileInputStream fileInputStream = new FileInputStream(cachedSites);
				ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream);
				cache = (HashMap<String,File>)objectInputStream.readObject();
				fileInputStream.close();
				objectInputStream.close();
			}

			// Carregar em sites bloqueados do arquivo
			File blockedSitesTxtFile = new File("blockedSites.txt");
			if(!blockedSitesTxtFile.exists()){
				System.out.println("No blocked sites found - creating new file");
				blockedSitesTxtFile.createNewFile();
			} else {
				FileInputStream fileInputStream = new FileInputStream(blockedSitesTxtFile);
				ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream);
				blockedSites = (HashMap<String, String>)objectInputStream.readObject();
				fileInputStream.close();
				objectInputStream.close();
			}
		} catch (IOException e) {
			System.out.println("Error loading previously cached sites file");
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			System.out.println("Class not found loading in preivously cached sites file");
			e.printStackTrace();
		}

		try {
			// Criar o soquete (Socket) do servidor para o proxy
			serverSocket = new ServerSocket(port);

			// Definir o tempo limite
			//serverSocket.setSoTimeout(100000);	// debug
			System.out.println("Waiting for client on port " + serverSocket.getLocalPort() + "..");
			running = true;
		} 

		// Pegue as exceções associadas à abertura do soquete (Socket)
		catch (SocketException se) {
			System.out.println("Socket Exception when connecting to client");
			se.printStackTrace();
		}
		catch (SocketTimeoutException ste) {
			System.out.println("Timeout occured while connecting to client");
		} 
		catch (IOException io) {
			System.out.println("IO exception when connecting to client");
		}
	}


	/**
	 * Escuta a porta e aceita novas conexões de soquete. 
	 * Cria um novo thread para manipular a solicitação e passa a conexão do soquete e continua ouvindo.
	 */
	public void listen(){

		while(running){
			try {
				// serverSocket.accpet() Bloqueia até que uma conexão seja feita
				Socket socket = serverSocket.accept();
				
				// Criar novo thread e passá-lo RequestHandler Runnable
				Thread thread = new Thread(new RequestHandler(socket));
				
				// Digite uma referência para cada thread para que eles possam ser associados posteriormente, se necessário
				servicingThreads.add(thread);
				
				thread.start();	
			} catch (SocketException e) {
				// Exceção de soquete é acionada pelo sistema de gerenciamento para desligar o proxy
				System.out.println("Server closed");
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}


	/**
	 * Salva os sites bloqueados e armazenados em cache em um arquivo para que eles possam ser reutilizados posteriormente.
	 * Também une todas as solicitações de serviço atualmente no ThreadHandler.
	 */
	private void closeServer(){
		System.out.println("\nClosing Server..");
		running = false;
		try{
			FileOutputStream fileOutputStream = new FileOutputStream("cachedSites.txt");
			ObjectOutputStream objectOutputStream = new ObjectOutputStream(fileOutputStream);

			objectOutputStream.writeObject(cache);
			objectOutputStream.close();
			fileOutputStream.close();
			System.out.println("Cached Sites written");

			FileOutputStream fileOutputStream2 = new FileOutputStream("blockedSites.txt");
			ObjectOutputStream objectOutputStream2 = new ObjectOutputStream(fileOutputStream2);
			objectOutputStream2.writeObject(blockedSites);
			objectOutputStream2.close();
			fileOutputStream2.close();
			System.out.println("Blocked Site list saved");
			try{
				// Feche todos os encaminhamentos threads
				for(Thread thread : servicingThreads){
					if(thread.isAlive()){
						System.out.print("Waiting on "+  thread.getId()+" to close..");
						thread.join();
						System.out.println(" closed");
					}
				}
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

			} catch (IOException e) {
				System.out.println("Error saving cache/blocked sites");
				e.printStackTrace();
			}

			// Feche o soquete do servidor
			try{
				System.out.println("Terminating Connection");
				serverSocket.close();
			} catch (Exception e) {
				System.out.println("Exception closing proxy's server socket");
				e.printStackTrace();
			}

		}


		/**
		 * Procura por arquivo no cache
		 * @param url de arquivo solicitado
		 * @return File se o arquivo estiver em cache, null, caso contrário
		 */
		public static File getCachedPage(String url){
			return cache.get(url);
		}


		/**
		 * Adiciona uma nova página ao cache
		 * @param urlString URL de página da web para cache
		 * @param fileToCache File Objeto apontando para o arquivo colocado no cache
		 */
		public static void addCachedPage(String urlString, File fileToCache){
			cache.put(urlString, fileToCache);
		}

		/**
		 * Verifique se um URL está bloqueado pelo proxy
		 * @param url URL checar
		 * @return true se URL está bloqueado, caso contrário false
		 */
		public static boolean isBlocked (String url){
			if(blockedSites.get(url) != null){
				return true;
			} else {
				return false;
			}
		}




		/**
		 * Cria uma interface de gerenciamento que pode atualizar dinamicamente as configurações de proxy
		 * 	bloqueado : Lista sites atualmente bloqueados
		 *  	em cache : Lista sites atualmente armazenados em cache
		 *  	fechar	: Fecha o servidor proxy
		 *  	*		: Adiciona * à lista de sites bloqueados
		 */
		@Override
		public void run() {
			Scanner scanner = new Scanner(System.in);

			String command;
			while(running){
				System.out.println("Enter new site to block, or type \"blocked\" to see blocked sites, \"cached\" to see cached sites, or \"close\" to close server.");
				command = scanner.nextLine();
				if(command.toLowerCase().equals("blocked")){
					System.out.println("\nCurrently Blocked Sites");
					for(String key : blockedSites.keySet()){
						System.out.println(key);
					}
					System.out.println();
				} 

				else if(command.toLowerCase().equals("cached")){
					System.out.println("\nCurrently Cached Sites");
					for(String key : cache.keySet()){
						System.out.println(key);
					}
					System.out.println();
				}


				else if(command.equals("close")){
					running = false;
					closeServer();
				}


				else {
					blockedSites.put(command, command);
					System.out.println("\n" + command + " blocked successfully \n");
				}
			}
			scanner.close();
		} 

	}
