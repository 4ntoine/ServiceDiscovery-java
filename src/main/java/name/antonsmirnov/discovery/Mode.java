package name.antonsmirnov.discovery;

/**
* Response mode
*/
public enum Mode {
    /**
     * Publisher will connect to responsePort over TCP
     */
    TCP,

    /**
     * Publisher will send multicast UDP to multicastGroup to responsePort
     */
    UDP
}
