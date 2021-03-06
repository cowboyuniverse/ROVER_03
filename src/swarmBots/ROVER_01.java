package swarmBots;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import common.Coord;
import common.MapTile;
import common.Rover;
import common.ScienceDetail;
import enums.RoverConfiguration;
import enums.RoverDriveType;
import enums.RoverMode;
import enums.RoverToolType;
import enums.Terrain;
import rover_logic.Astar;

/**
 * The seed that this program is built on is a chat program example found here:
 * http://cs.lmu.edu/~ray/notes/javanetexamples/ Many thanks to the authors for
 * publishing their code examples
 */

public class ROVER_01 extends Rover {

	public ROVER_01() {
		// constructor
		System.out.println("ROVER_01 rover object constructed");
		rovername = "ROVER_01";
	}

	public ROVER_01(String serverAddress) {
		// constructor
		System.out.println("ROVER_01 rover object constructed");
		rovername = "ROVER_01";
		SERVER_ADDRESS = serverAddress;
	}

	static enum Direction {
		NORTH, SOUTH, EAST, WEST;

		static Map<Character, Direction> map = new HashMap<Character, Direction>() {

			private static final long serialVersionUID = 1L;

			{
				put('N', NORTH);
				put('S', SOUTH);
				put('E', EAST);
				put('W', WEST);
			}
		};

		static Direction get(char c) {

			return map.get(c);
		}
	}

	static class MoveTargetLocation {

		Coord targetCoord;

		Direction d;
	}

	static Map<Coord, Integer> coordVisitCountMap = new HashMap<Coord, Integer>() {

		private static final long serialVersionUID = 1L;

		@Override
		public Integer get(Object key) {

			if (!containsKey(key)) {
				super.put((Coord) key, new Integer(0));
			}
			return super.get(key);
		}
	};

	Coord maxCoord = new Coord(0, 0);

	/**
	 * Connects to the server then enters the processing loop.
	 */
	private void run() throws IOException, InterruptedException {

		// Make connection to SwarmServer and initialize streams
		Socket socket = null;
		try {
			socket = new Socket(SERVER_ADDRESS, PORT_ADDRESS);

			receiveFrom_RCP = new BufferedReader(
					new InputStreamReader(socket.getInputStream()));
			sendTo_RCP = new PrintWriter(socket.getOutputStream(), true);

			// Need to allow time for the connection to the server to be
			// established
			sleepTime = 300;

			// Process all messages from server, wait until server requests
			// Rover ID
			// name - Return Rover Name to complete connection

			while (true) {
				String line = receiveFrom_RCP.readLine();
				if (line.startsWith("SUBMITNAME")) {
					// This sets the name of this instance of a swarmBot for
					// identifying the thread to the server
					sendTo_RCP.println(rovername);
					break;
				}
			}

			/**
			 * ### Setting up variables to be used in the Rover control loop ###
			 */

			Direction currentDir = Direction.NORTH;

			/**
			 * ### Retrieve static values from RCP ###
			 */
			// **** get equipment listing ****
			equipment = getEquipment();
			System.out.println(
					rovername + " equipment list results " + equipment + "\n");

			// **** Request START_LOC Location from SwarmServer **** this might
			// be dropped as it should be (0, 0)
			startLocation = getStartLocation();
			System.out.println(rovername + " START_LOC " + startLocation);

			// **** Request TARGET_LOC Location from SwarmServer ****
			targetLocation = getTargetLocation();
			System.out.println(rovername + " TARGET_LOC " + targetLocation);

			Astar aStar = new Astar();
			RoverMode roverMode = RoverMode.EXPLORE;
			ScienceDetail scienceDetail = null;

			/**
			 * #### Rover controller process loop ####
			 */
			while (true) { // <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<

				// **** Request Rover Location from RCP ****
				currentLoc = getCurrentLocation();

				System.out.println(rovername + " currentLoc at start: "
						+ currentLoc + " maxCoord: " + maxCoord);

				// after getting location set previous equal current to be able
				// to check for stuckness and blocked later
				previousLoc = currentLoc;

				// ***** do a SCAN *****
				// gets the scanMap from the server based on the Rover current
				// location
				scanMap = doScan();
				// prints the scanMap to the Console output for debug purposes
				scanMap.debugPrintMap();

				MapTile[][] scanMapTiles = scanMap.getScanMap();
				int mapTileCenter = (scanMap.getEdgeSize() - 1) / 2;
				Coord currentLocInMapTile = new Coord(mapTileCenter,
						mapTileCenter);

				int maxX = currentLoc.xpos + mapTileCenter;
				int maxY = currentLoc.ypos + mapTileCenter;
				if (maxCoord.xpos < maxX && maxCoord.ypos < maxY) {
					maxCoord = new Coord(maxX, maxY);
				} else if (maxCoord.xpos < maxX) {
					maxCoord = new Coord(maxX, maxCoord.ypos);
				} else if (maxCoord.ypos < maxY) {
					maxCoord = new Coord(maxCoord.xpos, maxY);
				}

				// ***** MOVING *****
				MoveTargetLocation moveTargetLocation = null;

				if (scienceDetail == null) {
					scienceDetail = analyzeAndGetSuitableScience();
				}

				if (scienceDetail != null) {
					roverMode = RoverMode.GATHER;
				} else {
					roverMode = RoverMode.EXPLORE;
				}

				//////////////////////////////////////
				if (roverMode == RoverMode.GATHER) {

					System.out.println(
							"FOUND SCIENCE TO GATHER: " + scienceDetail);

					// The rover is at the location of science, so gather
					if (scienceDetail.getX() == currentLoc.xpos
							&& scienceDetail.getY() == currentLoc.ypos) {
						gatherScience(currentLoc);
						System.out.println("$$$$$> Gathered science "
								+ scienceDetail.getScience() + " at location "
								+ currentLoc);
						scienceDetail = null;
						roverMode = RoverMode.EXPLORE;
					} else {
						RoverConfiguration roverConfiguration = RoverConfiguration
								.valueOf(rovername);
						RoverDriveType driveType = RoverDriveType.valueOf(
								roverConfiguration.getMembers().get(0));
						RoverToolType tool1 = RoverToolType.getEnum(
								roverConfiguration.getMembers().get(1));
						RoverToolType tool2 = RoverToolType.getEnum(
								roverConfiguration.getMembers().get(2));

						aStar.addScanMap(doScan(), currentLoc, tool1, tool2);

						char dirChar = aStar
								.findPath(currentLoc,
										new Coord(scienceDetail.getX(),
												scienceDetail.getY()),
										driveType);

						moveTargetLocation = new MoveTargetLocation();
						moveTargetLocation.d = Direction.get(dirChar);

						System.out.println(
								"=====> In gather mode using Astar in the direction "
										+ dirChar);
					}

				} else {
					moveTargetLocation = chooseMoveTargetLocation(scanMapTiles,
							currentLocInMapTile, currentLoc, mapTileCenter,
							currentDir);

					System.out
							.println("*****> In explore mode in the direction "
									+ moveTargetLocation.d);
				}

				if (moveTargetLocation != null
						&& moveTargetLocation.d != null) {
					switch (moveTargetLocation.d) {
					case NORTH:
						moveNorth();
						currentDir = Direction.NORTH;
						break;
					case EAST:
						moveEast();
						currentDir = Direction.EAST;
						break;
					case SOUTH:
						moveSouth();
						currentDir = Direction.SOUTH;
						break;
					case WEST:
						moveWest();
						currentDir = Direction.WEST;
						break;
					}

					if (!previousLoc.equals(getCurrentLocation())) {
						coordVisitCountMap
								.put(moveTargetLocation.targetCoord,
										coordVisitCountMap
												.get(moveTargetLocation.targetCoord)
												+ 1);
					}
				}

				try {
					sendRoverDetail(roverMode);

					postScanMapTiles();
				} catch (Exception e) {
					System.err.println(
							"Post current map to communication server failed. Cause: "
									+ e.getClass().getName() + ": "
									+ e.getMessage());
				}

				// this is the Rovers HeartBeat, it regulates how fast the Rover
				// cycles through the control loop
				Thread.sleep(sleepTime);

				System.out.println(
						"ROVER_01 ------------ bottom process control --------------");
			} // END of Rover control While(true) loop

			// This catch block closes the open socket connection to the server
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			if (socket != null) {
				try {
					socket.close();
				} catch (IOException e) {
					System.out.println("ROVER_01 problem closing socket");
				}
			}
		}

	} // END of Rover run thread

	private Coord getCoordNorthOf(Coord c) {

		return new Coord(c.xpos, c.ypos - 1);
	}

	private Coord getCoordEastOf(Coord c) {

		return new Coord(c.xpos + 1, c.ypos);
	}

	private Coord getCoordSouthOf(Coord c) {

		return new Coord(c.xpos, c.ypos + 1);
	}

	private Coord getCoordWestOf(Coord c) {

		return new Coord(c.xpos - 1, c.ypos);
	}

	private boolean isBlocked(MapTile[][] mapTiles, Coord c) {

		return mapTiles[c.xpos][c.ypos].getHasRover()
				|| mapTiles[c.xpos][c.ypos].getTerrain() == Terrain.SAND
				|| mapTiles[c.xpos][c.ypos].getTerrain() == Terrain.ROCK
				|| mapTiles[c.xpos][c.ypos].getTerrain() == Terrain.NONE;
	}

	private MoveTargetLocation chooseMoveTargetLocation(
			MapTile[][] scanMapTiles, Coord currentLocInMapTile,
			Coord currentLoc, int mapTileCenter, Direction currentDir) {

		Coord northCoordInMapTile = getCoordNorthOf(currentLocInMapTile);
		Coord eastCoordInMapTile = getCoordEastOf(currentLocInMapTile);
		Coord southCoordInMapTile = getCoordSouthOf(currentLocInMapTile);
		Coord westCoordInMapTile = getCoordWestOf(currentLocInMapTile);

		Coord northCoord = getCoordNorthOf(currentLoc);
		Coord eastCoord = getCoordEastOf(currentLoc);
		Coord southCoord = getCoordSouthOf(currentLoc);
		Coord westCoord = getCoordWestOf(currentLoc);

		int min = Integer.MAX_VALUE;

		MoveTargetLocation moveTargetLocation = new MoveTargetLocation();

		if (!isBlocked(scanMapTiles,
				getNextCoord(currentDir, currentLocInMapTile))) {
			switch (currentDir) {
			case NORTH:
				moveTargetLocation.targetCoord = northCoord;
				moveTargetLocation.d = Direction.NORTH;
				break;
			case EAST:
				moveTargetLocation.targetCoord = eastCoord;
				moveTargetLocation.d = Direction.EAST;
				break;
			case WEST:
				moveTargetLocation.targetCoord = westCoord;
				moveTargetLocation.d = Direction.WEST;
				break;
			case SOUTH:
				moveTargetLocation.targetCoord = southCoord;
				moveTargetLocation.d = Direction.SOUTH;
				break;
			default:
				break;
			}
		} else {
			System.out.println("next tile is block, change direction");
			if (!isBlocked(scanMapTiles,
					getNextCoord(Direction.NORTH, currentLocInMapTile))
					&& !currentDir.equals(Direction.SOUTH)) {
				moveTargetLocation.targetCoord = northCoord;
				moveTargetLocation.d = Direction.NORTH;
			} else if (!isBlocked(scanMapTiles,
					getNextCoord(Direction.EAST, currentLocInMapTile))
					&& !currentDir.equals(Direction.WEST)) {
				moveTargetLocation.targetCoord = eastCoord;
				moveTargetLocation.d = Direction.EAST;
			} else if (!isBlocked(scanMapTiles,
					getNextCoord(Direction.SOUTH, currentLocInMapTile))
					&& !currentDir.equals(Direction.NORTH)) {
				moveTargetLocation.targetCoord = southCoord;
				moveTargetLocation.d = Direction.SOUTH;
			} else {
				moveTargetLocation.targetCoord = westCoord;
				moveTargetLocation.d = Direction.WEST;
			}
		}

		Stack<Direction> favoredDirStack = getFavoredDirStack(currentLoc,
				mapTileCenter);

		while (!favoredDirStack.isEmpty()) {
			Direction d = favoredDirStack.pop();
			switch (d) {
			case NORTH:
				if (!isBlocked(scanMapTiles, northCoordInMapTile)
						&& coordVisitCountMap.get(northCoord) < min) {
					min = coordVisitCountMap.get(northCoord);
					moveTargetLocation.targetCoord = northCoord;
					moveTargetLocation.d = Direction.NORTH;
				}
				break;
			case EAST:
				if (!isBlocked(scanMapTiles, eastCoordInMapTile)
						&& coordVisitCountMap.get(eastCoord) < min) {
					min = coordVisitCountMap.get(eastCoord);
					moveTargetLocation.targetCoord = eastCoord;
					moveTargetLocation.d = Direction.EAST;
				}
				break;
			case SOUTH:
				if (!isBlocked(scanMapTiles, southCoordInMapTile)
						&& coordVisitCountMap.get(southCoord) < min) {
					min = coordVisitCountMap.get(southCoord);
					moveTargetLocation.targetCoord = southCoord;
					moveTargetLocation.d = Direction.SOUTH;
				}
				break;
			case WEST:
				if (!isBlocked(scanMapTiles, westCoordInMapTile)
						&& coordVisitCountMap.get(westCoord) < min) {
					min = coordVisitCountMap.get(westCoord);
					moveTargetLocation.targetCoord = westCoord;
					moveTargetLocation.d = Direction.WEST;
				}
			}
		}
		printMoveTargetLocation(moveTargetLocation);
		return moveTargetLocation;
	}

	public Coord getNextCoord(Direction direction, Coord currentLocInMapTile) {
		Coord nextCoord = null;
		switch (direction) {
		case NORTH:
			nextCoord = getCoordNorthOf(currentLocInMapTile);
			break;
		case EAST:
			nextCoord = getCoordEastOf(currentLocInMapTile);
			break;
		case WEST:
			nextCoord = getCoordWestOf(currentLocInMapTile);
			break;
		case SOUTH:
			nextCoord = getCoordSouthOf(currentLocInMapTile);
			break;
		default:
			break;
		}

		return nextCoord;
	}

	private Stack<Direction> getFavoredDirStack(Coord currentLoc,
			int mapTileCenter) {

		int northUnvisitedCount = 0, eastUnvisitedCount = 0,
				southUnvisitedCount = 0, westUnvisitedCount = 0;
		for (int x = 0; x < currentLoc.xpos; x++) {
			if (coordVisitCountMap.get(new Coord(x, currentLoc.ypos)) == 0) {
				westUnvisitedCount++;
			}
		}
		for (int x = currentLoc.xpos; x < maxCoord.xpos; x++) {
			if (coordVisitCountMap.get(new Coord(x, currentLoc.ypos)) == 0) {
				eastUnvisitedCount++;
			}
		}
		for (int y = 0; y < currentLoc.ypos; y++) {
			if (coordVisitCountMap.get(new Coord(currentLoc.xpos, y)) == 0) {
				northUnvisitedCount++;
			}
		}
		for (int y = currentLoc.ypos; y < maxCoord.ypos; y++) {
			if (coordVisitCountMap.get(new Coord(currentLoc.xpos, y)) == 0) {
				southUnvisitedCount++;
			}
		}
		List<Integer> countList = Arrays.asList(northUnvisitedCount,
				eastUnvisitedCount, southUnvisitedCount, westUnvisitedCount);
		Collections.sort(countList);

		Stack<Direction> directionStack = new Stack<>();

		for (Integer count : countList) {
			if (count == northUnvisitedCount
					&& !directionStack.contains(Direction.NORTH)) {
				directionStack.push(Direction.NORTH);
			}
			if (count == eastUnvisitedCount
					&& !directionStack.contains(Direction.EAST)) {
				directionStack.push(Direction.EAST);
			}
			if (count == southUnvisitedCount
					&& !directionStack.contains(Direction.SOUTH)) {
				directionStack.push(Direction.SOUTH);
			}
			if (count == westUnvisitedCount
					&& !directionStack.contains(Direction.WEST)) {
				directionStack.push(Direction.WEST);
			}
		}
		System.out.println("counts = North(" + northUnvisitedCount + ") East("
				+ eastUnvisitedCount + ") South(" + southUnvisitedCount
				+ ") West(" + westUnvisitedCount + ")");
		// System.out.println("countList = " + countList);
		System.out.println("favoredDirStack = " + directionStack);
		// System.out.println("coordVisitCountMap = " + coordVisitCountMap);

		return directionStack;
	}

	private void printMoveTargetLocation(
			MoveTargetLocation moveTargetLocation) {

		System.out.println("MoveTargetLocation.x = "
				+ moveTargetLocation.targetCoord.xpos);
		System.out.println("MoveTargetLocation.y = "
				+ moveTargetLocation.targetCoord.ypos);
		System.out.println("MoveTargetLocation.d = " + moveTargetLocation.d);
	}

	// ####################### Support Methods #############################

	/**
	 * Runs the client
	 */
	public static void main(String[] args) throws Exception {

		ROVER_01 client;
		// if a command line argument is present it is used
		// as the IP address for connection to SwarmServer instead of localhost

		if (!(args.length == 0)) {
			client = new ROVER_01(args[0]);
		} else {
			client = new ROVER_01();
		}

		client.run();
	}
}