```mermaid
graph LR
    Main --> Board
    Main --> InputParser
    Main --> Move
    Main --> MoveGenerator
    Main --> Piece
    Main --> Search
    Main --> Uci

    Uci --> Board
    Uci --> FenParser
    Uci --> InputParser
    Uci --> Move
    Uci --> Piece
    Uci --> Search

    Search --> Board
    Search --> Evaluator
    Search --> GameState
    Search --> Move
    Search --> MoveGenerator
    Search --> Piece
    Search --> Uci

    Evaluator --> Board
    Evaluator --> MoveGenerator
    Evaluator --> Piece

    MoveGenerator --> Board
    MoveGenerator --> Move
    MoveGenerator --> Piece

    InputParser --> Board
    InputParser --> GameState
    InputParser --> Move
    InputParser --> MoveGenerator

    FenParser --> Board
    FenParser --> InputParser
    FenParser --> Piece

    Board --> FenParser
    Board --> GameState
    Board --> Move
    Board --> Piece

    %% Leaf types (no project dependencies)
    GameState
    Move
    Piece

    classDef leaf fill:#eef,stroke:#557,stroke-width:1px;
    class GameState,Move,Piece leaf;