import groovy.transform.Field

metadata {
    definition (name: "D-Link DCH-Z3100",namespace: "lacke", author: "Tommy Lacke") {
        capability "Smoke Detector"
        capability "Sensor"
        capability "Battery"

        attribute "alarmState", "string"
		attribute "mount", "string"
        attribute "battery", "String"
        attribute "alarm", "boolean"
        attribute "test", "boolean"

		fingerprint endpointId: "01", profileId: "0104", deviceId: "001E", inClusters: "0x5E,0x80,0x71,0x85,0x70,0x72,0x86,0x30,0x84,0x59,0x73,0x5A,0x98,0x7A", manufacturer: "D-Link", model: "dlink.smokeSensor"

    	command "getBatteryLevel"
    }

	simulator {
		status "smoke": "command: 7105, payload: 00 00 00 FF 01 02 00"
		status "clear": "command: 7105, payload: 00 00 00 FF 01 00 00"
		status "test": "command: 7105, payload: 00 00 00 FF 01 03 00"
        status "detached": "command: 7105, payload: 00 00 00 FF 07 03 00"
		status "battery 100%": "command: 8003, payload: 64"
		status "battery 5%": "command: 8003, payload: 05"
	}

    preferences {
        input name: "LogInfo", type: "bool", title: "Enable info message logging", defaultValue: true
		input name: "LogDebug", type: "bool", title: "Enable debug message logging", defaultValue: false
    }

	tiles (scale: 2){
		multiAttributeTile(name:"smoke", type: "lighting", width: 6, height: 4){
			tileAttribute ("device.alarmState", key: "PRIMARY_CONTROL") {
				attributeState("clear", label:"clear", icon:"st.alarm.smoke.clear", backgroundColor:"#ffffff")
				attributeState("smoke", label:"SMOKE", icon:"st.alarm.smoke.smoke", backgroundColor:"#f85d13")
				attributeState("test", label:"test", icon:"st.alarm.smoke.test", backgroundColor:"#d85d13")
				attributeState("detached", label:"detached", icon:"st.alarm.smoke.detached", backgroundColor:"#ff00ff")
			}
		}
		valueTile("battery", "device.battery", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
			state "battery", label:'${currentValue}% battery', unit:""
		}
		valueTile("mount", "device.mount", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
            state "mount", label:"${currentValue}", unit:""
		}

		main "smoke"
		details(["smoke", "battery", "mount"])
	}
}

def installed() {
    chkInterval()

	def cmds = []
	createCustomEvent("clear", cmds)
	cmds.each { cmd -> sendEvent(cmd) }
}

def updated() {
    chkInterval()
}

def chkInterval() {
    // Device checks in every hour, this interval allows us to miss one check-in notification before marking offline
	sendEvent(name: "checkInterval", value: 2 * 60 * 60 + 2 * 60, displayed: false, data: [protocol: "zwave", hubHardwareId: device.hub.hardwareID])
}

def configure() {}

def createCustomEvent(name, res) {
	def text = null
    def status = "idle"

	switch (name) {
        case "attached":
            text = "$device.displayName has been attached to wall/roof!"
            res << createEvent(name: "mount", value: "attached", descriptionText: text, displayed: false)
            state.mounted = true;
            break;

        case "detached":
            text = "$device.displayName has been detached from wall/roof!"
            res << createEvent(name: "mount", value: "detached", descriptionText: text, displayed: false)
            status = "detached"
            state.mounted = false;
            break;

        case "smoke":
			text = "$device.displayName smoke was detected!"
			// these are displayed:false because the composite event is the one we want to see in the app
			res << createEvent(name: "smoke", value: "detected", descriptionText: text, displayed: false)
            status = "smoke"
			break

		case "test":
			text = "$device.displayName was tested"
			res << createEvent(name: "smoke", value: "test", descriptionText: text, displayed: false)
            status = "test"
			break

		case "clear":
			text = "$device.displayName all clear"
			res << createEvent(name: "smoke", value: "clear", descriptionText: text, displayed: false)
            res << createEvent(name: "mount", value: "attached", displayed: false)
            state.mounted = false;
			break

	}

	// This composite event is used for updating the tile
	res << createEvent(name: "alarmState", value: status, descriptionText: text)
}



def parse(String description) {
	debug "Got notification: ${description}"
    
    def res = []
	if (description.startsWith("Err")) {
	    res << createEvent(descriptionText:description, displayed:true)

	} else {
		def cmd = zwave.parse(description)
		if (cmd) {
			zwEvent(cmd, res)
		}

	}

    return res;
}

//Z-Wave responses

// Wakeup (Quick press button)
def zwEvent(hubitat.zwave.commands.wakeupv2.WakeUpNotification cmd, res) {
	info "Wakup Notification"
	
	res << createEvent(descriptionText: "$device.displayName woke up", isStateChange: false)
    if (state.mounted==false) {
        createCustomEvent("attached", res)
    }
    
    if (!state.lastbatt || (now() - state.lastbatt) >= 56*60*60*1000) {
		res << response([
				zwave.batteryV1.batteryGet().format(),
				"delay 2000",
				zwave.wakeUpV1.wakeUpNoMoreInformation().format()
			])
	} else {
		res << response(zwave.wakeUpV1.wakeUpNoMoreInformation())
	}
}

// Battery information report
def zwEvent(hubitat.zwave.commands.batteryv1.BatteryReport cmd, res) {
    debug "Battery report..."

	def map = [ name: "battery", unit: "%", isStateChange: true ]
	state.lastbatt = now()
	if (cmd.batteryLevel <= 5 || cmd.batteryLevel == 0xFF) {
		map.value = 1
		map.descriptionText = "$device.displayName battery is low!"
        log.warn "Battery on $device.displayName is running low!"
	} else {
		map.value = cmd.batteryLevel
	}

    info "Battery reported ${map.value}%"

	res << createEvent(map)
}

// Alarm notification
def zwEvent(hubitat.zwave.commands.notificationv8.NotificationReport cmd, res) {
	debug "Alarm notification..."
    def desc;

    switch (cmd.payload[4]) {
        case cmd.NOTIFICATION_TYPE_BURGLAR:
            switch (cmd.payload[5]) {
                case 3:
                    createCustomEvent("detached", res)
                    break
            }
            break;
            
        case cmd.NOTIFICATION_TYPE_SMOKE:
            switch (cmd.payload[5]) {
                case 0:
			        createCustomEvent("clear", res)
                    break

                case 2:
			        createCustomEvent("smoke", res)
                    break

                case 3:
			        createCustomEvent("test", res)
                    break

                default:
                    desc = "Alarm: unknown ${cmd.payload[5]}"
                    log.warn "Unknown alarm event data: ${cmd.getProperties().toString()}"
                    break
            }
            break;

        default:
            desc = "Unknown event ${cmd.payload[4]}"
            log.warn "Unknown event data: ${cmd.getProperties().toString()}"
            break
    }
}

def zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd, results) {
    def encapCmd = cmd.encapsulatedCommand()
    def result = []
    if (encapCmd) {
		result += zwaveEvent(encapCmd)
    } else {
        log.warn "Unable to extract encapsulated cmd from ${cmd}"
    }
    return result
}

def zwEvent(comand) {
    info "unknown command: ${comand}"
}

//cmds
def getBatteryLevel(){
    info "Getting battery level"

    def cmds = []
	cmds << response([zwave.batteryV1.batteryGet().format()]);

    return cmds;
}

private secureCmd(cmd) {
    if (getDataValue("zwaveSecurePairingComplete") == "true") {
		return zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd).format()
    } else {
		return cmd.format()
    }	
}

private def info(message) {
	if (LogInfo) log.info "${device.displayName}: ${message}"
}

private def debug(message) {
	if (LogDebug) log.debug "${device.displayName}: ${message}"
}
