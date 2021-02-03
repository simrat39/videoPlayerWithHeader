// Copyright 2017 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'dart:async';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter/material.dart';
import 'package:meta/meta.dart';

final MethodChannel _channel = const MethodChannel('flutter.io/videoPlayer')
// This will clear all open videos on the platform when a full restart is
// performed.
  ..invokeMethod('init');

class DurationRange {
  DurationRange(this.start, this.end);

  final Duration start;
  final Duration end;

  double startFraction(Duration duration) {
    return start.inMilliseconds / duration.inMilliseconds;
  }

  double endFraction(Duration duration) {
    return end.inMilliseconds / duration.inMilliseconds;
  }

  @override
  String toString() => '$runtimeType(start: $start, end: $end)';
}

/// The duration, current position, buffering state, error state and settings
/// of a [VideoPlayerController].
class VideoPlayerValue {
  VideoPlayerValue({
    @required this.duration,
    this.size,
    this.position = const Duration(),
    this.buffered = const <DurationRange>[],
    this.isPlaying = false,
    this.isLooping = false,
    this.isBuffering = false,
    this.volume = 1.0,
    this.errorDescription,
  });

  VideoPlayerValue.uninitialized() : this(duration: null);

  VideoPlayerValue.erroneous(String errorDescription)
      : this(duration: null, errorDescription: errorDescription);

  /// The total duration of the video.
  ///
  /// Is null when [initialized] is false.
  final Duration duration;

  /// The current playback position.
  final Duration position;

  /// The currently buffered ranges.
  final List<DurationRange> buffered;

  /// True if the video is playing. False if it's paused.
  final bool isPlaying;

  /// True if the video is looping.
  final bool isLooping;

  /// True if the video is currently buffering.
  final bool isBuffering;

  /// The current volume of the playback.
  final double volume;

  /// A description of the error if present.
  ///
  /// If [hasError] is false this is [null].
  final String errorDescription;

  /// The [size] of the currently loaded video.
  ///
  /// Is null when [initialized] is false.
  final Size size;

  bool get initialized => duration != null;
  bool get hasError => errorDescription != null;
  double get aspectRatio => size.width / size.height;

  VideoPlayerValue copyWith({
    Duration duration,
    Size size,
    Duration position,
    List<DurationRange> buffered,
    bool isPlaying,
    bool isLooping,
    bool isBuffering,
    double volume,
    String errorDescription,
  }) {
    return VideoPlayerValue(
      duration: duration ?? this.duration,
      size: size ?? this.size,
      position: position ?? this.position,
      buffered: buffered ?? this.buffered,
      isPlaying: isPlaying ?? this.isPlaying,
      isLooping: isLooping ?? this.isLooping,
      isBuffering: isBuffering ?? this.isBuffering,
      volume: volume ?? this.volume,
      errorDescription: errorDescription ?? this.errorDescription,
    );
  }

  @override
  String toString() {
    return '$runtimeType('
        'duration: $duration, '
        'size: $size, '
        'position: $position, '
        'buffered: [${buffered.join(', ')}], '
        'isPlaying: $isPlaying, '
        'isLooping: $isLooping, '
        'isBuffering: $isBuffering'
        'volume: $volume, '
        'errorDescription: $errorDescription)';
  }
}

enum DataSourceType { asset, network, file }

/// Controls a platform video player, and provides updates when the state is
/// changing.
///
/// Instances must be initialized with initialize.
///
/// The video is displayed in a Flutter app by creating a [VideoPlayer] widget.
///
/// To reclaim the resources used by the player call [dispose].
///
/// After [dispose] all further calls are ignored.
class VideoPlayerController extends ValueNotifier<VideoPlayerValue> {
  /// Constructs a [VideoPlayerController] playing a video from an asset.
  ///
  /// The name of the asset is given by the [dataSource] argument and must not be
  /// null. The [package] argument must be non-null when the asset comes from a
  /// package and null otherwise.
  VideoPlayerController.asset(this.dataSource, {this.package})
      : dataSourceType = DataSourceType.asset,
        headers = null,
        super(VideoPlayerValue(duration: null));

  /// Constructs a [VideoPlayerController] playing a video from obtained from
  /// the network.
  ///
  /// The URI for the video is given by the [dataSource] argument and must not be
  /// null.
  VideoPlayerController.network(this.dataSource, {this.headers})
      : dataSourceType = DataSourceType.network,
        package = null,
        super(VideoPlayerValue(duration: null));

  /// Constructs a [VideoPlayerController] playing a video from a file.
  ///
  /// This will load the file from the file-URI given by:
  /// `'file://${file.path}'`.
  VideoPlayerController.file(File file)
      : dataSource = 'file://${file.path}',
        dataSourceType = DataSourceType.file,
        package = null,
        headers = null,
        super(VideoPlayerValue(duration: null));

  int _textureId;
  final String dataSource;
  final Map<String, String> headers;

  /// Describes the type of data source this [VideoPlayerController]
  /// is constructed with.
  final DataSourceType dataSourceType;

  final String package;
  Timer _timer;
  bool _isDisposed = false;
  Completer<void> _creatingCompleter;
  StreamSubscription<dynamic> _eventSubscription;
  _VideoAppLifeCycleObserver _lifeCycleObserver;
  double _oldSpeed = 1.0;

  @visibleForTesting
  int get textureId => _textureId;

  Future<void> initialize() async {
    _lifeCycleObserver = _VideoAppLifeCycleObserver(this);
    _lifeCycleObserver.initialize();
    _creatingCompleter = Completer<void>();
    Map<dynamic, dynamic> dataSourceDescription;
    switch (dataSourceType) {
      case DataSourceType.asset:
        dataSourceDescription = <String, dynamic>{
          'asset': dataSource,
          'package': package
        };
        break;
      case DataSourceType.network:
        dataSourceDescription = <String, dynamic>{
          'uri': dataSource,
          'headers': headers
        };
        break;
      case DataSourceType.file:
        dataSourceDescription = <String, dynamic>{'uri': dataSource};
    }
    final Map<dynamic, dynamic> response = await _channel.invokeMethod(
      'create',
      dataSourceDescription,
    );
    _textureId = response['textureId'];
    _creatingCompleter.complete(null);
    final Completer<void> initializingCompleter = Completer<void>();

    DurationRange toDurationRange(dynamic value) {
      final List<dynamic> pair = value;
      return DurationRange(
        Duration(milliseconds: pair[0]),
        Duration(milliseconds: pair[1]),
      );
    }

    void eventListener(dynamic event) {
      final Map<dynamic, dynamic> map = event;
      switch (map['event']) {
        case 'initialized':
          value = value.copyWith(
            duration: Duration(milliseconds: map['duration']),
            size: Size(map['width']?.toDouble() ?? 0.0,
                map['height']?.toDouble() ?? 0.0),
          );
          initializingCompleter.complete(null);
          _applyLooping();
          _applyVolume();
          _applyPlayPause();
          break;
        case 'completed':
          value = value.copyWith(isPlaying: false);
          _timer?.cancel();
          break;
        case 'bufferingUpdate':
          final List<dynamic> values = map['values'];
          value = value.copyWith(
            buffered: values.map<DurationRange>(toDurationRange).toList(),
          );
          break;
        case 'bufferingStart':
          value = value.copyWith(isBuffering: true);
          break;
        case 'bufferingEnd':
          value = value.copyWith(isBuffering: false);
          break;
        case 'paused':
          value = value.copyWith(isPlaying: false);
          break;
        case 'played':
          value = value.copyWith(isPlaying: true);
          break;
      }
    }

    void errorListener(Object obj) {
      final PlatformException e = obj;
      value = VideoPlayerValue.erroneous(e.message);
      _timer?.cancel();
    }

    _eventSubscription = _eventChannelFor(_textureId)
        .receiveBroadcastStream()
        .listen(eventListener, onError: errorListener);
    return initializingCompleter.future;
  }

  EventChannel _eventChannelFor(int textureId) {
    return EventChannel('flutter.io/videoPlayer/videoEvents$textureId');
  }

  @override
  Future<void> dispose() async {
    if (_creatingCompleter != null) {
      await _creatingCompleter.future;
      if (!_isDisposed) {
        _isDisposed = true;
        _timer?.cancel();
        await _eventSubscription?.cancel();
        await _channel.invokeMethod(
          'dispose',
          <String, dynamic>{'textureId': _textureId},
        );
      }
      _lifeCycleObserver.dispose();
    }
    _isDisposed = true;
    super.dispose();
  }

  Future<void> play() async {
    value = value.copyWith(isPlaying: true);
    await _applyPlayPause();
  }

  Future<void> setLooping(bool looping) async {
    value = value.copyWith(isLooping: looping);
    await _applyLooping();
  }

  Future<void> pause() async {
    value = value.copyWith(isPlaying: false);
    await _applyPlayPause();
  }

  Future<void> _applyLooping() async {
    if (!value.initialized || _isDisposed) {
      return;
    }
    _channel.invokeMethod(
      'setLooping',
      <String, dynamic>{'textureId': _textureId, 'looping': value.isLooping},
    );
  }

  Future<void> _applyPlayPause() async {
    if (!value.initialized || _isDisposed) {
      return;
    }
    if (value.isPlaying) {
      await _channel.invokeMethod(
        'play',
        <String, dynamic>{'textureId': _textureId},
      );
      _timer = Timer.periodic(
        const Duration(milliseconds: 500),
        (Timer timer) async {
          if (_isDisposed) {
            return;
          }
          final Duration newPosition = await position;
          if (_isDisposed) {
            return;
          }
          value = value.copyWith(position: newPosition);
        },
      );
    } else {
      _timer?.cancel();
      await _channel.invokeMethod(
        'pause',
        <String, dynamic>{'textureId': _textureId},
      );
    }
  }

  Future<void> _applyVolume() async {
    if (!value.initialized || _isDisposed) {
      return;
    }
    await _channel.invokeMethod(
      'setVolume',
      <String, dynamic>{'textureId': _textureId, 'volume': value.volume},
    );
  }

  /// The position in the current video.
  Future<Duration> get position async {
    if (_isDisposed) {
      return null;
    }
    return Duration(
      milliseconds: await _channel.invokeMethod(
        'position',
        <String, dynamic>{'textureId': _textureId},
      ),
    );
  }

  Future<void> seekTo(Duration moment) async {
    if (_isDisposed) {
      return;
    }
    if (moment > value.duration) {
      moment = value.duration;
    } else if (moment < const Duration()) {
      moment = const Duration();
    }
    await _channel.invokeMethod('seekTo', <String, dynamic>{
      'textureId': _textureId,
      'location': moment.inMilliseconds,
    });
    value = value.copyWith(position: moment);
  }

  Future<void> setPlaybackSpeed(double speed) async {
    if (_oldSpeed == speed) return;
    await _channel.invokeMethod('setPlaybackSpeed', <String, dynamic>{
      'textureId': _textureId,
      'speed': speed,
    });
    _oldSpeed = speed;
  }

  Future<double> getPlaybackSpeed() async {
    return await _channel.invokeMethod('getPlaybackSpeed');
  }

  /// Sets the audio volume of [this].
  ///
  /// [volume] indicates a value between 0.0 (silent) and 1.0 (full volume) on a
  /// linear scale.
  Future<void> setVolume(double volume) async {
    value = value.copyWith(volume: volume.clamp(0.0, 1.0));
    await _applyVolume();
  }
}

class _VideoAppLifeCycleObserver extends Object with WidgetsBindingObserver {
  _VideoAppLifeCycleObserver(this._controller);

  bool _wasPlayingBeforePause = false;
  final VideoPlayerController _controller;

  void initialize() {
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    switch (state) {
      case AppLifecycleState.paused:
        _wasPlayingBeforePause = _controller.value.isPlaying;
        _controller.pause();
        break;
      case AppLifecycleState.resumed:
        if (_wasPlayingBeforePause) {
          _controller.play();
        }
        break;
      default:
    }
  }

  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
  }
}

/// Displays the video controlled by [controller].
class VideoPlayer extends StatefulWidget {
  VideoPlayer(this.controller);

  final VideoPlayerController controller;

  @override
  _VideoPlayerState createState() => _VideoPlayerState();
}

class _VideoPlayerState extends State<VideoPlayer> {
  _VideoPlayerState() {
    _listener = () {
      final int newTextureId = widget.controller.textureId;
      if (newTextureId != _textureId) {
        setState(() {
          _textureId = newTextureId;
        });
      }
    };
  }

  VoidCallback _listener;
  int _textureId;

  @override
  void initState() {
    super.initState();
    _textureId = widget.controller.textureId;
    // Need to listen for initialization events since the actual texture ID
    // becomes available after asynchronous initialization finishes.
    widget.controller.addListener(_listener);
  }

  @override
  void didUpdateWidget(VideoPlayer oldWidget) {
    super.didUpdateWidget(oldWidget);
    oldWidget.controller.removeListener(_listener);
    _textureId = widget.controller.textureId;
    widget.controller.addListener(_listener);
  }

  @override
  void deactivate() {
    super.deactivate();
    widget.controller.removeListener(_listener);
  }

  @override
  Widget build(BuildContext context) {
    return _textureId == null ? Container() : Texture(textureId: _textureId);
  }
}
